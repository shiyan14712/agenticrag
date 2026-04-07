package com.yoswell.agenticrag.core.agent.service.orchestrator;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import lombok.RequiredArgsConstructor;
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

import dev.langchain4j.service.TokenStream;
import tools.jackson.databind.ObjectMapper;

/**
 * ReAct 过程编排器 —— 将 LangChain4J Agent 的内部推理链实时暴露给前端
 *
 * <p>核心职责：在 LLM 的 Thought → Action → Observation 循环的每个阶段，
 * 通过 SSE 事件通道向前端推送实时状态，驱动前端的交互动画（CoT 思考、工具调用、最终回答）。</p>
 *
 * <p>SSE 事件时间线（典型 ReAct 循环）：</p>
 * <pre>
 *   User Query 进入
 *     ↓
 *   [thinking]    "正在分析您的问题..."（LLM reasoning token 流）
 *     ↓
 *   [tool_start]  { tool: "search_enterprise_knowledge", status: "executing" }
 *     ↓
 *   [tool_result] { tool: "search_enterprise_knowledge", status: "completed", resultSummary: "检索到 5 条" }
 *     ↓
 *   [thinking]    "正在深入解析检索结果..."（LLM 继续推理）
 *     ↓
 *   [message]     token token token...（最终回答流式输出）
 *     ↓
 *   [citations]   [{ docId, chunkId, ... }]
 *     ↓
 *   [done]        { }
 * </pre>
 *
 * <p>依赖 LangChain4J 1.2.0+ 的 {@link TokenStream} 回调：
 * {@code onPartialThinking}、{@code beforeToolExecution}、{@code onToolExecuted}、
 * {@code onPartialResponse}、{@code onCompleteResponse}。</p>
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
     * 启动 ReAct Agent Loop 推理循环
     * @param sessionId 会话 ID
     * @param message 用户消息
     * @return SseEmitter 流式响应
     */
    public SseEmitter dispatchDynamicStream(String sessionId, String message) {
        SseEmitter emitter = new SseEmitter(10L * 60 * 1000); // 10 minutes timeout
        
        Thread.startVirtualThread(() -> {
            AutoCloseable retrievalScope = null;
            try {
                chatMessageService.saveUserMessage(sessionId, message);
                asyncTitleGenerationIfNeeded(sessionId, message);

                StringBuilder fullResponse = new StringBuilder();
                ragRetrievalContextHolder.clearSessionResult(sessionId);
                retrievalScope = ragRetrievalContextHolder.bindCurrentThread(sessionId);
                final AutoCloseable finalRetrievalScope = retrievalScope;
                
                // 用于计算工具执行耗时
                AtomicLong toolStartTimestamp = new AtomicLong(0);
                
                log.info("[ReAct] Agent 推理循环启动: session={}", sessionId);
                TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                tokenStream
                    // ────────────────────────────────────────
                    // 阶段 1: CoT 推理过程（Thinking）
                    // LLM 的内部推理 token，前端渲染为"正在思考..."动画
                    // 注意：需要 LLM 后端支持 reasoning/thinking token 输出
                    // ────────────────────────────────────────
                        // TODO: 此处需要适配 vLLM Inference 流式返回格式
                    .onPartialThinking(partialThinking -> {
                        bindRagContextToCurrentThread(sessionId);
                        String thinkingText = partialThinking.text();
                        if (thinkingText != null && !thinkingText.isEmpty()) {
                            emitSseEvent(emitter, SseEventType.THINKING, thinkingText);
                        }
                    })
                    // ────────────────────────────────────────
                    // 阶段 2: Tool 开始执行（beforeToolExecution）
                    // LLM 决定调用工具，前端渲染为加载动画 + "正在检索知识库..."
                    // ────────────────────────────────────────
                    .beforeToolExecution(beforeTool -> {
                        bindRagContextToCurrentThread(sessionId);
                        toolStartTimestamp.set(System.currentTimeMillis());
                        String toolName = beforeTool.request().name();
                        String argsPreview = summarizeToolArgs(beforeTool.request().arguments());
                        log.info("[ReAct] Tool 即将执行: tool={}, args={}", toolName, argsPreview);

                        ToolEventDTO startEvent = ToolEventDTO.executing(toolName, argsPreview);
                        emitSseEventJson(emitter, SseEventType.TOOL_START, startEvent);
                    })
                    // ────────────────────────────────────────
                    // 阶段 3: Tool 执行完成（onToolExecuted）
                    // 工具返回结果，前端渲染为完成卡片
                    // ────────────────────────────────────────
                    .onToolExecuted(toolExecution -> {
                        bindRagContextToCurrentThread(sessionId);
                        long elapsed = System.currentTimeMillis() - toolStartTimestamp.get();
                        String toolName = toolExecution.request().name();
                        String resultPreview = summarizeToolResult(toolExecution.result());
                        log.info("[ReAct] Tool 执行完成: tool={}, elapsed={}ms, resultPreview={}", toolName, elapsed, resultPreview);

                        ToolEventDTO resultEvent = ToolEventDTO.completed(toolName, resultPreview, elapsed);
                        emitSseEventJson(emitter, SseEventType.TOOL_RESULT, resultEvent);
                    })
                    // ────────────────────────────────────────
                    // 阶段 4: 最终回答流式输出（Message）
                    // ────────────────────────────────────────
                    .onPartialResponse(token -> {
                        bindRagContextToCurrentThread(sessionId);
                        fullResponse.append(token);
                        emitSseEvent(emitter, SseEventType.MESSAGE, token);
                    })
                    // ────────────────────────────────────────
                    // 阶段 5: 整个 ReAct 循环结束
                    // ────────────────────────────────────────
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
                    .onError(error -> {
                        bindRagContextToCurrentThread(sessionId);
                        try {
                            log.error("[ReAct] TokenStream 执行异常", error);
                            emitSseEventJson(emitter, SseEventType.ERROR,
                                    new ErrorPayload("AGENT_ERROR", error.getMessage()));
                            emitter.completeWithError(error);
                        } finally {
                            closeQuietly(finalRetrievalScope);
                            ragRetrievalContextHolder.clearSessionBindings(sessionId);
                            ragRetrievalContextHolder.clearSessionResult(sessionId);
                        }
                    })
                    .start();
            } catch (Exception e) {
                log.error("[ReAct] 虚拟线程执行异常", e);
                emitter.completeWithError(e);
                closeQuietly(retrievalScope);
                ragRetrievalContextHolder.clearSessionBindings(sessionId);
                ragRetrievalContextHolder.clearSessionResult(sessionId);
            }
        });
        
        return emitter;
    }

    // ================================================================
    // SSE 推送方法
    // ================================================================

    /**
     * 推送纯文本 SSE 事件（如 thinking token、message token）
     */
    private void emitSseEvent(SseEmitter emitter, SseEventType eventType, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(data));
        } catch (Exception e) {
            log.error("[ReAct] SSE 推送失败: event={}", eventType.getValue(), e);
        }
    }

    /**
     * 推送 JSON 序列化的 SSE 事件（如 tool_start、tool_result、error）
     */
    private void emitSseEventJson(SseEmitter emitter, SseEventType eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(json));
        } catch (Exception e) {
            log.error("[ReAct] SSE JSON 推送失败: event={}", eventType.getValue(), e);
        }
    }

    private void emitCitationsWidget(SseEmitter emitter, List<CitationDTO> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        emitSseEventJson(emitter, SseEventType.CITATIONS, citations);
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /** 异步标题生成（首条消息时触发） */
    private void asyncTitleGenerationIfNeeded(String sessionId, String message) {
        String titleGenKey = ChatCacheConstants.SESSION_TITLE_GEN_PREFIX + sessionId;
        Boolean isFirstMessage = stringRedisTemplate.opsForValue().setIfAbsent(titleGenKey, "1", Duration.ofHours(24));
        
        if (Boolean.TRUE.equals(isFirstMessage)) {
            ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .select(ChatSession::getSessionId, ChatSession::getTitle)
            );
            if (session != null && session.getTitle() == null) {
                Thread.startVirtualThread(() -> {
                    log.info("[ReAct] 异步生成会话标题: session={}", sessionId);
                    try {
                        chatService.generateTitleAndSave(sessionId, message);
                    } catch (Exception e) {
                        log.error("[ReAct] 标题生成失败，移除 Redis 锁以允许重试", e);
                        stringRedisTemplate.delete(titleGenKey);
                    }
                });
            }
        }
    }

    /** 工具参数摘要（截取前 80 字符） */
    private String summarizeToolArgs(String arguments) {
        if (arguments == null) return "<无参数>";
        String normalized = arguments.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    /** 工具结果摘要（截取前 120 字符） */
    private String summarizeToolResult(String result) {
        if (result == null) return "<无结果>";
        String normalized = result.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private void bindRagContextToCurrentThread(String sessionId) {
        ragRetrievalContextHolder.registerCurrentThread(sessionId);
    }

    private void closeQuietly(AutoCloseable scope) {
        if (scope == null) return;
        try { scope.close(); } catch (Exception e) { log.debug("Failed to close rag retrieval scope cleanly", e); }
    }

    /** 错误事件的 JSON 载荷 */
    private record ErrorPayload(String code, String message) {}
}
