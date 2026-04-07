package com.yoswell.agenticrag.core.agent.service.orchestrator;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.constants.ChatCacheConstants;
import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.SseEventType;
import com.yoswell.agenticrag.core.agent.dto.ToolEventDTO;
import com.yoswell.agenticrag.core.agent.service.ChatService;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * ReAct 流式对话编排器
 *
 * <p>
 * 职责分为三部分：</p>
 * <p>
 * 1. 驱动 LangChain4j 的流式推理链路，并将关键阶段转换成 SSE 事件推给前端</p>
 * <p>
 * 2. 在流式会话生命周期内维护检索上下文、工具执行耗时与引用聚合</p>
 * <p>
 * 3. 处理会话级副作用，例如用户消息落库、助手消息落库，以及首轮消息后的异步标题生成</p>
 */
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final EnterpriseAgent enterpriseAgent;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;
    private final ChatService chatService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatSessionMapper chatSessionMapper;

    /**
     * 发起一轮 ReAct 流式对话
     *
     * <p>
     * 统一执行顺序如下：</p>
     * <p>
     * 1. 先保存用户消息，并尝试触发“仅首轮执行一次”的异步标题生成</p>
     * <p>
     * 2. 绑定当前会话的检索上下文，启动 Agent 的 TokenStream</p>
     * <p>
     * 3. 将 thinking / tool_start / tool_result / message / citations / done
     * 逐步发给前端</p>
     * <p>
     * 4. 在完成或失败时清理上下文绑定，避免线程复用污染后续请求</p>
     *
     * @param sessionId 会话 ID
     * @param message 用户输入
     * @param tenantUser 当前请求经 JWT 验证后的用户身份快照
     * @return SSE 发射器
     */
    public SseEmitter dispatchDynamicStream(String sessionId, String message, TenantUser tenantUser) {
        SseEmitter emitter = new SseEmitter(10L * 60 * 1000);

        AutoCloseable retrievalScope = null;
        try {
            ragRetrievalContextHolder.registerSessionPrincipal(sessionId, tenantUser);
            chatMessageService.saveUserMessage(sessionId, message);
            asyncTitleGenerationIfNeeded(sessionId, message);

            StringBuilder fullResponse = new StringBuilder();
            ragRetrievalContextHolder.clearSessionResult(sessionId);
            retrievalScope = ragRetrievalContextHolder.bindCurrentThread(sessionId);
            final AutoCloseable finalRetrievalScope = retrievalScope;
            AtomicLong toolStartTimestamp = new AtomicLong(0);

            log.info("[ReAct] Agent 推理循环启动: session={}", sessionId);
            TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
            tokenStream
                    // 阶段 1：流式思考过程前端通常会渲染成“正在思考”或 reasoning 面板
                    .onPartialThinking(partialThinking -> {
                        bindRagContextToCurrentThread(sessionId);
                        String thinkingText = partialThinking.text();
                        if (thinkingText != null && !thinkingText.isEmpty()) {
                            emitSseEvent(emitter, SseEventType.THINKING, thinkingText);
                        }
                    })
                    // 阶段 2：工具开始执行这里记录开始时间，并把工具名与参数摘要发给前端
                    .beforeToolExecution(beforeTool -> {
                        bindRagContextToCurrentThread(sessionId);
                        toolStartTimestamp.set(System.currentTimeMillis());
                        String toolName = beforeTool.request().name();
                        String argsPreview = summarizeToolArgs(beforeTool.request().arguments());
                        log.info("[ReAct] Tool 即将执行: tool={}, args={}", toolName, argsPreview);

                        ToolEventDTO startEvent = ToolEventDTO.executing(toolName, argsPreview);
                        emitSseEventJson(emitter, SseEventType.TOOL_START, startEvent);
                    })
                    // 阶段 3：工具执行完成这里计算耗时，并把结果摘要发给前端
                    .onToolExecuted(toolExecution -> {
                        bindRagContextToCurrentThread(sessionId);
                        long elapsed = System.currentTimeMillis() - toolStartTimestamp.get();
                        String toolName = toolExecution.request().name();
                        String resultPreview = summarizeToolResult(toolExecution.result());
                        log.info("[ReAct] Tool 执行完成: tool={}, elapsed={}ms, resultPreview={}",
                                toolName, elapsed, resultPreview);

                        ToolEventDTO resultEvent = ToolEventDTO.completed(toolName, resultPreview, elapsed);
                        emitSseEventJson(emitter, SseEventType.TOOL_RESULT, resultEvent);
                    })
                    // 阶段 4：最终回答流式输出每个 token 都会推给前端，并拼接完整回答
                    .onPartialResponse(token -> {
                        bindRagContextToCurrentThread(sessionId);
                        fullResponse.append(token);
                        emitSseEvent(emitter, SseEventType.MESSAGE, token);
                    })
                    // 阶段 5：整轮会话完成聚合引用、发送结束事件、保存助手消息并清理上下文
                    .onCompleteResponse(response -> {
                        bindRagContextToCurrentThread(sessionId);
                        try {
                            List<CitationDTO> citations = ragRetrievalContextHolder.consume(sessionId)
                                    .map(RagSearchResultDTO::citations)
                                    .orElse(List.of());
                            emitCitationsWidget(emitter, citations);
                            emitSseEvent(emitter, SseEventType.DONE, "{}");

                            chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                            emitter.complete();
                            log.info("[ReAct] Agent 推理循环结束: session={}", sessionId);
                        } finally {
                            closeQuietly(finalRetrievalScope);
                            ragRetrievalContextHolder.clearSessionBindings(sessionId);
                        }
                    })
                    // 异常阶段：发送 error 事件，并回收检索上下文与会话结果缓存
                    .onError(error -> {
                        bindRagContextToCurrentThread(sessionId);
                        try {
                            log.error("[ReAct] TokenStream 执行异常", error);
                            emitSseEventJson(emitter, SseEventType.ERROR,
                                    new ErrorPayload("AGENT_ERROR", error.getMessage()));
                            emitter.complete();
                        } finally {
                            closeQuietly(finalRetrievalScope);
                            ragRetrievalContextHolder.clearSessionBindings(sessionId);
                            ragRetrievalContextHolder.clearSessionResult(sessionId);
                        }
                    })
                    .start();
        } catch (Exception e) {
            log.error("[ReAct] 虚拟线程执行异常", e);
            emitSseEventJson(emitter, SseEventType.ERROR,
                    new ErrorPayload("AGENT_ERROR", e.getMessage()));
            emitter.complete();
            closeQuietly(retrievalScope);
            ragRetrievalContextHolder.clearSessionBindings(sessionId);
            ragRetrievalContextHolder.clearSessionResult(sessionId);
        }

        return emitter;
    }

    /**
     * 发送纯文本 SSE 事件
     *
     * <p>
     * 适用于 thinking 与 message 这类天然按 token 流动的文本事件</p>
     */
    private void emitSseEvent(SseEmitter emitter, SseEventType eventType, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(data));
        } catch (Exception e) {
            log.error("[ReAct] SSE 推送失败: event={}", eventType.getValue(), e);
        }
    }

    /**
     * 发送 JSON 类型 SSE 事件
     *
     * <p>
     * 适用于 tool_start、tool_result、error、citations 这类结构化载荷</p>
     */
    private void emitSseEventJson(SseEmitter emitter, SseEventType eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(json));
        } catch (Exception e) {
            log.error("[ReAct] SSE JSON 推送失败: event={}", eventType.getValue(), e);
        }
    }

    /**
     * 发送引用卡片事件
     *
     * <p>
     * 只有存在引用时才发送，避免前端为“空引用”渲染无意义组件</p>
     */
    private void emitCitationsWidget(SseEmitter emitter, List<CitationDTO> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        emitSseEventJson(emitter, SseEventType.CITATIONS, citations);
    }

    /**
     * 首轮消息后异步生成会话标题
     *
     * <p>
     * 使用 Redis 锁避免重复生成；如果生成失败，则主动释放锁，允许后续消息重新触发</p>
     */
    private void asyncTitleGenerationIfNeeded(String sessionId, String message) {
        String titleGenKey = ChatCacheConstants.SESSION_TITLE_GEN_PREFIX + sessionId;
        Boolean isFirstMessage = stringRedisTemplate.opsForValue().setIfAbsent(titleGenKey, "1", Duration.ofHours(24));

        if (!Boolean.TRUE.equals(isFirstMessage)) {
            log.info("[ReAct] 跳过异步标题生成，已有进行中的生成锁: session={}", sessionId);
            return;
        }

        ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .select(ChatSession::getSessionId, ChatSession::getTitle));
        if (session == null) {
            log.warn("[ReAct] 跳过异步标题生成，会话不存在: session={}", sessionId);
            stringRedisTemplate.delete(titleGenKey);
            return;
        }
        if (session.getTitle() != null && !session.getTitle().isBlank()) {
            log.info("[ReAct] 跳过异步标题生成，会话已有标题: session={}, title={}", sessionId, session.getTitle());
            return;
        }

        Thread.startVirtualThread(() -> {
            log.info("[ReAct] 异步生成会话标题: session={}", sessionId);
            try {
                chatService.generateTitleAndSave(sessionId, message);
            } catch (Exception e) {
                log.error("[ReAct] 标题生成失败，移除 Redis 锁以允许重试: session={}", sessionId, e);
                stringRedisTemplate.delete(titleGenKey);
            }
        });
    }

    /**
     * 生成工具参数摘要
     *
     * <p>
     * 统一压缩空白符，并限制长度，避免长参数把日志和前端状态卡片刷屏</p>
     */
    private String summarizeToolArgs(String arguments) {
        if (arguments == null) {
            return "<无参数>";
        }
        String normalized = arguments.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    /**
     * 生成工具结果摘要
     *
     * <p>
     * 只保留前 120 个字符，方便日志定位问题，同时避免原始结果过长</p>
     */
    private String summarizeToolResult(String result) {
        if (result == null) {
            return "<无结果>";
        }
        String normalized = result.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    /**
     * 把当前回调线程重新注册到会话级检索上下文中
     *
     * <p>
     * 这是为了兼容流式回调与工具执行可能发生在线程切换上的情况</p>
     */
    private void bindRagContextToCurrentThread(String sessionId) {
        ragRetrievalContextHolder.registerCurrentThread(sessionId);
    }

    /**
     * 安静关闭作用域对象
     *
     * <p>
     * 这里不向外抛异常，避免清理阶段反向覆盖主异常</p>
     */
    private void closeQuietly(AutoCloseable scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Exception e) {
            log.debug("Failed to close rag retrieval scope cleanly", e);
        }
    }

    /**
     * SSE error 事件载荷
     */
    private record ErrorPayload(String code, String message) {

    }
}
