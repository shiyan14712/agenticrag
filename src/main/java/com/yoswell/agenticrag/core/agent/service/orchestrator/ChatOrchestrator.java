package com.yoswell.agenticrag.core.agent.service.orchestrator;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.ttl.TtlRunnable;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.constants.ChatCacheConstants;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.BusinessExceptionMapper;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.constants.ToolExecutionConstants;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.SseEventType;
import com.yoswell.agenticrag.core.agent.dto.ToolEventDTO;
import com.yoswell.agenticrag.core.agent.service.ChatService;
import com.yoswell.agenticrag.core.agent.tool.PreferenceTool;
import com.yoswell.agenticrag.core.agent.tool.RagTool;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * ReAct 流式对话编排器
 *
 * <p>
 * 职责分为三部分：
 * </p>
 * <p>
 * 1. 驱动 LangChain4j 的流式推理链路，并将关键阶段转换成 SSE 事件推给前端
 * </p>
 * <p>
 * 2. 在流式会话生命周期内维护检索上下文、工具执行耗时与引用聚合
 * </p>
 * <p>
 * 3. 处理会话级副作用，例如用户消息落库、助手消息落库，以及首轮消息后的异步标题生成
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private static final int MAX_AGENT_TURNS = 12;

    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final RagTool ragTool;
    private final PreferenceTool preferenceTool;
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
     * 统一执行顺序如下：
     * </p>
     * <p>
     * 1. 先保存用户消息，并尝试触发“仅首轮执行一次”的异步标题生成
     * </p>
     * <p>
     * 2. 绑定当前会话的检索上下文，启动 Agent 的 TokenStream
     * </p>
     * <p>
     * 3. 将 thinking / tool_start / tool_result / message / citations / done
     * 逐步发给前端
     * </p>
     * <p>
     * 4. 在完成或失败时清理上下文绑定，避免线程复用污染后续请求
     * </p>
     *
     * @param sessionId  会话 ID
     * @param message    用户输入
     * @param tenantUser 当前请求经 JWT 验证后的用户身份快照
     * @return SSE 发射器
     */
    public SseEmitter dispatchDynamicStream(String sessionId, String message, TenantUser tenantUser) {
        SseEmitter emitter = new SseEmitter(10L * 60 * 1000);
        log.info("[ReAct] 接收到流式对话请求: session={}, userId={}, tenantId={}, messageChars={}",
                sessionId,
                tenantUser == null ? "<unknown>" : tenantUser.getUserId(),
                tenantUser == null ? "<unknown>" : tenantUser.getTenantId(),
                message == null ? 0 : message.length());

        AutoCloseable retrievalScope = null;
        try {
            ragRetrievalContextHolder.registerSessionPrincipal(sessionId, tenantUser);
            chatMessageService.saveUserMessage(sessionId, message);
            asyncTitleGenerationIfNeeded(sessionId, message);
            log.debug("[ReAct] 会话上下文初始化完成: session={}, principalSnapshotReady={}",
                    sessionId, tenantUser != null);

            StringBuilder fullResponse = new StringBuilder();
            ragRetrievalContextHolder.clearSessionResult(sessionId);
            retrievalScope = ragRetrievalContextHolder.bindCurrentThread(sessionId);
            final AutoCloseable finalRetrievalScope = retrievalScope;
            AtomicLong toolStartTimestamp = new AtomicLong(0);

            log.info("[ReAct] Agent 推理循环启动: session={}", sessionId);
            try {
                runExplicitAgentLoop(sessionId, message, emitter, fullResponse, toolStartTimestamp);

                List<CitationDTO> citations = ragRetrievalContextHolder.consume(sessionId)
                        .map(RagSearchResultDTO::citations)
                        .orElse(List.of());

                chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                emitCitationsWidget(emitter, citations);
                emitSseEvent(emitter, SseEventType.DONE, "{}");
                emitter.complete();
                log.info("[ReAct] Agent 推理循环结束: session={}, answerChars={}, citationCount={}",
                        sessionId, fullResponse.length(), citations.size());
            } catch (Exception exception) {
                BusinessException businessException = BusinessExceptionMapper.map(exception,
                        ErrorCode.AGENT_STREAM_INTERRUPTED);
                log.error("[ReAct] TokenStream 执行异常: session={}, code={}, message={}",
                        sessionId, businessException.getCode(), businessException.getMessage(), exception);
                emitBusinessErrorAndComplete(emitter, businessException);
                ragRetrievalContextHolder.clearSessionResult(sessionId);
            } finally {
                closeQuietly(finalRetrievalScope);
                ragRetrievalContextHolder.clearSessionBindings(sessionId);
            }
        } catch (Exception e) {
            BusinessException businessException = BusinessExceptionMapper.map(e, ErrorCode.AGENT_STREAM_INTERRUPTED);
            log.error("[ReAct] 会话启动失败: session={}, code={}, message={}",
                    sessionId, businessException.getCode(), businessException.getMessage(), e);
            emitBusinessErrorAndComplete(emitter, businessException);
            closeQuietly(retrievalScope);
            ragRetrievalContextHolder.clearSessionBindings(sessionId);
            ragRetrievalContextHolder.clearSessionResult(sessionId);
        }

        return emitter;
    }

    /**
     * 项目侧显式 Harness Loop。
     *
     * <p>
     * 与 LangChain4j AiServices 的黑盒 Tool Loop 不同，这里由业务代码掌控 turn 计数、
     * 中间响应日志、工具执行、Observation 回填和结束条件。
     * </p>
     */
    private void runExplicitAgentLoop(String sessionId,
                                      String message,
                                      SseEmitter emitter,
                                      StringBuilder fullResponse,
                                      AtomicLong toolStartTimestamp) {
        ChatMemory chatMemory = chatMemoryProvider.get(sessionId);
        chatMemory.add(UserMessage.from(message));

        ToolRuntime toolRuntime = buildToolRuntime();
        InvocationContext invocationContext = InvocationContext.builder()
                .chatMemoryId(sessionId)
                .build();

        for (int turn = 1; turn <= MAX_AGENT_TURNS; turn++) {
            bindRagContextToCurrentThread(sessionId);
            List<ChatMessage> messages = messagesWithSystemPrompt(chatMemory.messages());
            log.info("[Harness] Turn {} 开始: session={}, messageCount={}, toolCount={}",
                    turn, sessionId, messages.size(), toolRuntime.specifications().size());

            StringBuilder turnTextBuffer = new StringBuilder();
            ChatResponse response = streamOneModelTurn(
                    sessionId, turn, messages, toolRuntime.specifications(), emitter, turnTextBuffer);

            AiMessage aiMessage = response.aiMessage();
            chatMemory.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                appendFinalTurnTextIfNeeded(emitter, fullResponse, turnTextBuffer, aiMessage);
                log.info("[Harness] Turn {} 产生最终回答，Agent Loop 正常结束: session={}", turn, sessionId);
                return;
            }

            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
            log.info("[Harness] Turn {} 产生 {} 个 ToolExecutionRequest: session={}",
                    turn, toolRequests.size(), sessionId);
            if (!turnTextBuffer.isEmpty()) {
                emitSseEvent(emitter, SseEventType.THINKING, turnTextBuffer.toString());
            }

            List<ToolExecutionResultMessage> observations = executeToolRequests(
                    sessionId, turn, toolRequests, toolRuntime.executors(), invocationContext, emitter, toolStartTimestamp);
            observations.forEach(chatMemory::add);

            log.info("[Harness] Turn {} 工具 Observation 已写回 Memory，继续下一轮推理: session={}, observations={}",
                    turn, sessionId, observations.size());
        }

        throw new BusinessException(
                ErrorCode.AGENT_STREAM_INTERRUPTED.getCode(),
                "Agent Loop 超过最大轮数限制: " + MAX_AGENT_TURNS + ", 请手动继续");
    }

    private ChatResponse streamOneModelTurn(String sessionId,
                                            int turn,
                                            List<ChatMessage> messages,
                                            List<ToolSpecification> toolSpecifications,
                                            SseEmitter emitter,
                                            StringBuilder turnTextBuffer) {
        CompletableFuture<ChatResponse> completion = new CompletableFuture<>();
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .build();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                bindRagContextToCurrentThread(sessionId);
                if (partialResponse != null && !partialResponse.isEmpty()) {
                    turnTextBuffer.append(partialResponse);
                }
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                bindRagContextToCurrentThread(sessionId);
                String thinkingText = partialThinking.text();
                if (thinkingText != null && !thinkingText.isEmpty()) {
                    emitSseEvent(emitter, SseEventType.THINKING, thinkingText);
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                bindRagContextToCurrentThread(sessionId);
                log.debug("[Harness] Turn {} 正在生成 ToolCall: session={}, index={}, tool={}, partialArgs={}",
                        turn,
                        sessionId,
                        partialToolCall.index(),
                        partialToolCall.name(),
                        summarizeToolArgs(partialToolCall.partialArguments()));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                bindRagContextToCurrentThread(sessionId);
                completion.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                completion.completeExceptionally(error);
            }
        });

        try {
            ChatResponse response = completion.join();
            AiMessage aiMessage = response.aiMessage();
            log.info("[Harness] Turn {} 模型响应完成: session={}, hasToolRequests={}, finishReason={}",
                    turn, sessionId, aiMessage.hasToolExecutionRequests(), response.finishReason());
            return response;
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BusinessException(
                    ErrorCode.AGENT_STREAM_INTERRUPTED.getCode(),
                    "Agent Loop 模型流式响应失败: " + cause.getMessage());
        }
    }

    private List<ToolExecutionResultMessage> executeToolRequests(String sessionId,
                                                                 int turn,
                                                                 List<ToolExecutionRequest> toolRequests,
                                                                 Map<String, ToolExecutor> toolExecutors,
                                                                 InvocationContext invocationContext,
                                                                 SseEmitter emitter,
                                                                 AtomicLong toolStartTimestamp) {
        List<ToolExecutionResultMessage> observations = new ArrayList<>(toolRequests.size());
        for (ToolExecutionRequest toolRequest : toolRequests) {
            bindRagContextToCurrentThread(sessionId);
            toolStartTimestamp.set(System.currentTimeMillis());
            String toolName = toolRequest.name();
            String argsPreview = summarizeToolArgs(toolRequest.arguments());
            log.info("[Harness] Tool 即将执行: session={}, tool={}, args={}", sessionId, toolName, argsPreview);
            chatMessageService.saveAssistantToolCallMessage(sessionId, toolRequest, turn);
            emitSseEventJson(emitter, SseEventType.TOOL_START, ToolEventDTO.executing(toolName, argsPreview));

            ToolExecutionResult result = executeSingleTool(toolRequest, toolExecutors, invocationContext);
            long elapsed = System.currentTimeMillis() - toolStartTimestamp.get();
            String resultText = result.resultText() == null ? "" : result.resultText();
            ToolExecutionOutcome outcome = decodeToolExecutionOutcome(resultText);
            boolean failed = result.isError() || outcome.failed();
            String resultPreview = summarizeToolResult(outcome.message());
            chatMessageService.saveToolResultMessage(sessionId, toolRequest, result, failed, elapsed, turn);

            if (failed) {
                log.warn("[Harness] Tool 执行失败: session={}, tool={}, elapsed={}ms, resultPreview={}",
                        sessionId, toolName, elapsed, resultPreview);
                emitSseEventJson(emitter, SseEventType.TOOL_RESULT, ToolEventDTO.failed(toolName, resultPreview));
            } else {
                log.info("[Harness] Tool 执行完成: session={}, tool={}, elapsed={}ms, resultPreview={}",
                        sessionId, toolName, elapsed, resultPreview);
                emitSseEventJson(emitter, SseEventType.TOOL_RESULT,
                        ToolEventDTO.completed(toolName, resultPreview, elapsed));
            }

            observations.add(ToolExecutionResultMessage.builder()
                    .id(toolRequest.id())
                    .toolName(toolName)
                    .text(resultText)
                    .isError(failed)
                    .attributes(result.attributes())
                    .build());
        }
        return observations;
    }

    private ToolExecutionResult executeSingleTool(ToolExecutionRequest toolRequest,
                                                  Map<String, ToolExecutor> toolExecutors,
                                                  InvocationContext invocationContext) {
        ToolExecutor executor = toolExecutors.get(toolRequest.name());
        if (executor == null) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("Unknown tool requested by model: " + toolRequest.name())
                    .build();
        }
        try {
            return executor.executeWithContext(toolRequest, invocationContext);
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            log.error("[Harness] Tool 执行异常: tool={}, args={}", toolRequest.name(), toolRequest.arguments(), exception);
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText(message)
                    .build();
        }
    }

    private void appendFinalTurnTextIfNeeded(SseEmitter emitter,
                                             StringBuilder fullResponse,
                                             StringBuilder turnTextBuffer,
                                             AiMessage aiMessage) {
        String finalText = !turnTextBuffer.isEmpty() ? turnTextBuffer.toString() : aiMessage.text();
        if (finalText == null || finalText.isEmpty()) {
            return;
        }
        fullResponse.append(finalText);
        emitSseEvent(emitter, SseEventType.MESSAGE, finalText);
    }

    private List<ChatMessage> messagesWithSystemPrompt(List<ChatMessage> memoryMessages) {
        List<ChatMessage> messages = new ArrayList<>(memoryMessages.size() + 1);
        messages.add(SystemMessage.from("""
                You are an enterprise AI assistant.
                Use tools sequentially if needed. Maintain a professional tone.
                Reason step by step before calling a tool.
                For complex research tasks, decompose the request into multiple searches when the current observations are insufficient.
                After each tool observation, decide whether another search is needed or whether the final answer can be produced.
                If retrieval failed, do not answer arbitrarily; indicate that no content was found.
                """));
        messages.addAll(memoryMessages);
        return messages;
    }

    private ToolRuntime buildToolRuntime() {
        Map<String, ToolExecutor> executors = new LinkedHashMap<>();
        List<ToolSpecification> specifications = new ArrayList<>();
        registerToolObject(ragTool, specifications, executors);
        registerToolObject(preferenceTool, specifications, executors);
        return new ToolRuntime(List.copyOf(specifications), Map.copyOf(executors));
    }

    private void registerToolObject(Object toolObject,
                                    List<ToolSpecification> specifications,
                                    Map<String, ToolExecutor> executors) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            specifications.add(specification);
            executors.put(specification.name(), new DefaultToolExecutor(toolObject, method));
            log.debug("[Harness] 注册工具: tool={}, class={}, method={}",
                    specification.name(), toolObject.getClass().getSimpleName(), method.getName());
        }
    }

    /**
     * 发送纯文本 SSE 事件
     *
     * <p>
     * 适用于 thinking 与 message 这类天然按 token 流动的文本事件
     * </p>
     */
    private void emitSseEvent(SseEmitter emitter, SseEventType eventType, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(data));
        } catch (IOException | IllegalStateException e) {
            log.error("[ReAct] SSE 推送失败: event={}", eventType.getValue(), e);
        }
    }

    /**
     * 发送 JSON 类型 SSE 事件
     *
     * <p>
     * 适用于 tool_start、tool_result、error、citations 这类结构化载荷
     * </p>
     */
    private void emitSseEventJson(SseEmitter emitter, SseEventType eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(eventType.getValue()).data(json));
        } catch (JacksonException | IOException | IllegalStateException e) {
            log.error("[ReAct] SSE JSON 推送失败: event={}", eventType.getValue(), e);
        }
    }

    /**
     * 发送业务错误并结束 SSE
     */
    private void emitBusinessErrorAndComplete(SseEmitter emitter, BusinessException businessException) {
        ErrorPayload payload = ErrorPayload.fromBusinessException(businessException);
        log.warn("[ReAct] 发送错误事件并结束 SSE: code={}, message={}", payload.code(), payload.message());
        emitSseEventJson(emitter, SseEventType.ERROR,
                payload);
        emitSseEvent(emitter, SseEventType.DONE, "{}");
        emitter.complete();
    }

    /**
     * 发送引用卡片事件
     *
     * <p>
     * 只有存在引用时才发送，避免前端为“空引用”渲染无意义组件
     * </p>
     */
    private void emitCitationsWidget(SseEmitter emitter, List<CitationDTO> citations) {
        if (citations == null || citations.isEmpty()) {
            log.debug("[ReAct] 本轮未产生引用，不发送 citations 事件");
            return;
        }
        log.info("[ReAct] 发送 citations 事件: citationCount={}", citations.size());
        emitSseEventJson(emitter, SseEventType.CITATIONS, citations);
    }

    /**
     * 首轮消息后异步生成会话标题
     *
     * <p>
     * 使用 Redis 锁避免重复生成；如果生成失败，则主动释放锁，允许后续消息重新触发
     * </p>
     */
    private void asyncTitleGenerationIfNeeded(String sessionId, String message) {
        String titleGenKey = ChatCacheConstants.SESSION_TITLE_GEN_PREFIX + sessionId;
        Boolean isFirstMessage = stringRedisTemplate.opsForValue().setIfAbsent(titleGenKey, "1", Duration.ofHours(24));

        if (!Boolean.TRUE.equals(isFirstMessage)) {
            log.info("[ReAct] 跳过异步标题生成，已有进行中的生成锁: session={}", sessionId);
            return;
        }

        ChatSessionDO session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>()
                .eq(ChatSessionDO::getSessionId, sessionId)
                .select(ChatSessionDO::getSessionId, ChatSessionDO::getTitle));
        if (session == null) {
            log.warn("[ReAct] 跳过异步标题生成，会话不存在: session={}", sessionId);
            stringRedisTemplate.delete(titleGenKey);
            return;
        }
        if (session.getTitle() != null && !session.getTitle().isBlank()) {
            log.info("[ReAct] 跳过异步标题生成，会话已有标题: session={}, title={}", sessionId, session.getTitle());
            return;
        }

        Runnable titleTask = TtlRunnable.get(() -> {
            log.info("[ReAct] 异步生成会话标题: session={}", sessionId);
            try {
                chatService.generateTitleAndSave(sessionId, message);
            } catch (Exception e) {
                BusinessException businessException = BusinessExceptionMapper.map(e, ErrorCode.TITLE_GENERATION_FAILED);
                log.error("[ReAct] 标题生成失败，移除 Redis 锁以允许重试: session={}, code={}, message={}",
                        sessionId, businessException.getCode(), businessException.getMessage(), e);
                stringRedisTemplate.delete(titleGenKey);
            }
        });
        Thread.ofVirtual()
                .name("title-gen[" + sessionId + "]")
                .start(titleTask);
    }

    /**
     * 生成工具参数摘要
     *
     * <p>
     * 统一压缩空白符，并限制长度，避免长参数把日志和前端状态卡片刷屏
     * </p>
     */
    private String summarizeToolArgs(String arguments) {
        if (arguments == null) {
            return "<NO_PARAMETER>";
        }
        String normalized = arguments.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    /**
     * 生成工具结果摘要
     *
     * <p>
     * 只保留前 120 个字符，方便日志定位问题，同时避免原始结果过长
     * </p>
     */
    private String summarizeToolResult(String result) {
        if (result == null) {
            return "<NO_TOOL_RESULT>";
        }
        String normalized = result.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    /**
     * 解码工具执行结果
     *
     * <p>
     * 约定：Tool 返回 "__TOOL_SUCCESS__ ..." / "__TOOL_FAILED__ ..." 前缀时，
     * 编排器据此推送 completed / failed 卡片；其余结果默认视为 completed
     * </p>
     */
    private ToolExecutionOutcome decodeToolExecutionOutcome(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            log.debug("[ReAct] Tool 执行结果为空，按成功空结果处理");
            return ToolExecutionOutcome.empty();
        }
        String normalizedMessage = ToolExecutionConstants.stripMarker(rawResult);
        if (ToolExecutionConstants.isFailedResult(rawResult)) {
            ToolExecutionOutcome failedOutcome = ToolExecutionOutcome.failed(normalizedMessage);
            log.warn("[ReAct] 识别到 Tool 失败标记: messagePreview={}", summarizeToolResult(failedOutcome.message()));
            return failedOutcome;
        }
        if (ToolExecutionConstants.isSuccessResult(rawResult)) {
            ToolExecutionOutcome successOutcome = ToolExecutionOutcome.succeeded(normalizedMessage);
            log.debug("[ReAct] 识别到 Tool 成功标记: messagePreview={}", summarizeToolResult(successOutcome.message()));
            return successOutcome;
        }
        ToolExecutionOutcome defaultOutcome = ToolExecutionOutcome.succeeded(normalizedMessage);
        log.debug("[ReAct] Tool 返回未包含标准标记，按成功处理: messagePreview={}", summarizeToolResult(defaultOutcome.message()));
        return defaultOutcome;
    }

    /**
     * 把当前回调线程重新注册到会话级检索上下文中
     *
     * <p>
     * 这是为了兼容流式回调与工具执行可能发生在线程切换上的情况
     * </p>
     */
    private void bindRagContextToCurrentThread(String sessionId) {
        ragRetrievalContextHolder.registerCurrentThread(sessionId);
    }

    /**
     * 安静关闭作用域对象
     *
     * <p>
     * 这里不向外抛异常，避免清理阶段反向覆盖主异常
     * </p>
     */
    private void closeQuietly(AutoCloseable scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Exception e) {
            log.debug("[ReAct] 关闭检索上下文作用域失败，已忽略", e);
        }
    }

    /**
     * SSE error 事件载荷
     *
     * <p>
     * 提供统一工厂方法，确保错误码与错误消息不会出现空值，便于前端稳定处理
     * </p>
     */
    private record ErrorPayload(String code, String message) {

        private static final String DEFAULT_ERROR_CODE = ErrorCode.SYSTEM_ERROR.getCode();
        private static final String DEFAULT_ERROR_MESSAGE = ErrorCode.SYSTEM_ERROR.getMessage();

        /**
         * 基于业务异常构建标准化错误载荷
         *
         * @param businessException 业务异常
         * @return 兜底后的错误载荷
         */
        public static ErrorPayload fromBusinessException(BusinessException businessException) {
            if (businessException == null) {
                return new ErrorPayload(DEFAULT_ERROR_CODE, DEFAULT_ERROR_MESSAGE);
            }
            String normalizedCode = hasText(businessException.getCode())
                    ? businessException.getCode()
                    : DEFAULT_ERROR_CODE;
            String normalizedMessage = hasText(businessException.getMessage())
                    ? businessException.getMessage()
                    : DEFAULT_ERROR_MESSAGE;
            return new ErrorPayload(normalizedCode, normalizedMessage);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

    }

    /**
     * Harness 层运行时工具注册表
     */
    private record ToolRuntime(List<ToolSpecification> specifications, Map<String, ToolExecutor> executors) {
    }

    /**
     * 工具执行结果解码后的统一视图
     *
     * <p>
     * 通过工厂方法统一处理空值与默认文案，避免编排器主流程里散落重复判断
     * </p>
     */
    private record ToolExecutionOutcome(boolean failed, String message) {

        private static final String DEFAULT_SUCCESS_MESSAGE = "Tool executed successfully";
        private static final String DEFAULT_FAILED_MESSAGE = "Tool execution failed";
        private static final String EMPTY_RESULT_MESSAGE = "<NO_TOOL_RESULT>";

        /**
         * 构造空结果场景（按成功处理）
         */
        public static ToolExecutionOutcome empty() {
            return new ToolExecutionOutcome(false, EMPTY_RESULT_MESSAGE);
        }

        /**
         * 构造成功结果
         */
        public static ToolExecutionOutcome succeeded(String message) {
            return new ToolExecutionOutcome(false, normalizeMessage(message, DEFAULT_SUCCESS_MESSAGE));
        }

        /**
         * 构造失败结果
         */
        public static ToolExecutionOutcome failed(String message) {
            return new ToolExecutionOutcome(true, normalizeMessage(message, DEFAULT_FAILED_MESSAGE));
        }

        private static String normalizeMessage(String message, String fallback) {
            if (message == null || message.isBlank()) {
                return fallback;
            }
            return message.trim();
        }

    }
}
