package com.yoswell.agenticrag.core.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.core.agent.ai.RagStructuredAgent;
import com.yoswell.agenticrag.core.agent.dto.RagStructuredResponseDTO;
import com.yoswell.agenticrag.core.agent.orchestrator.ChatOrchestrator;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 智能体对话控制器
 *
 * <p>提供与 RAG 智能体交互的 WebFlux 响应式接口。支持两种对话模式：
 * <ul>
 *     <li>流式问答模式 (SSE, Server-Sent Events)：适用于打字机效果的动态生成响应。</li>
 *     <li>结构化问答模式：一次性返回包含思考过程和最终结果的结构化 JSON。</li>
 * </ul>
 * 采用异步非阻塞架构，调用底层核心的 LLM 生成与知识库检索逻辑。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ChatOrchestrator chatOrchestrator;
    private final RagStructuredAgent ragStructuredAgent;
    private final SessionService sessionService;

    public AgentController(ChatOrchestrator chatOrchestrator,
                           RagStructuredAgent ragStructuredAgent,
                           SessionService sessionService) {
        this.chatOrchestrator = chatOrchestrator;
        this.ragStructuredAgent = ragStructuredAgent;
        this.sessionService = sessionService;
    }

    /**
     * 发送问题并获取流式对话响应 (Server-Sent Events)
     *
     * <p>根据用户传入的对话内容，调用 RAG 工作流，通过 SSE (Server-Sent Events) 的形式增量
     * 返回生成内容的片段，实现打字机输出效果。响应链路全称使用 WebFlux Reactive Stream。</p>
     *
     * @param sessionId 当前会话的唯一标识 (X-Session-Id Request Header)
     * @param message   用户的提问内容，以普通文本格式传入
     * @return 包含生成的流式文本片段的响应式 Flux 流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody String message) {
        return SecurityUtils.getCurrentUserId()
                .flatMapMany(userId -> {
                    sessionService.verifySessionAccess(sessionId, userId);
                    return chatOrchestrator.dispatchDynamicStream(sessionId, message);
                });
    }

    /**
     * 发送问题并获取结构化对话响应
     *
     * <p>调用结构化返回的 Agent 模式，一次性返回思考过程与最终结果。
     * 由于底层可能有部分阻塞逻辑，该方法被分发到 boundedElastic 线程池中执行。</p>
     *
     * @param sessionId 当前会话的唯一标识 (X-Session-Id Request Header)
     * @param message   用户的提问内容，以普通文本格式传入
     * @return 包含完整回答及参考段落的结构化数据的响应式 Mono 流
     */
    @PostMapping(value = "/chat/structured", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RagStructuredResponseDTO> chatStructured(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody String message) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> {
                    sessionService.verifySessionAccess(sessionId, userId);
                    return ragStructuredAgent.askStructured(sessionId, message);
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
