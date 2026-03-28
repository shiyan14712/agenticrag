package com.yoswell.agenticrag.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.model.RagStructuredResponse;
import com.yoswell.agenticrag.service.ChatOrchestrator;
import com.yoswell.agenticrag.service.RagStructuredAgent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ChatOrchestrator chatOrchestrator;
    private final RagStructuredAgent ragStructuredAgent;

    public AgentController(ChatOrchestrator chatOrchestrator, RagStructuredAgent ragStructuredAgent) {
        this.chatOrchestrator = chatOrchestrator;
        this.ragStructuredAgent = ragStructuredAgent;
    }

    /**
     * Scene 3 & 4: 统一对话流入口（前端主力接口）
     * 内部实现意图路由 (Router JSON Schema) + Markdown 文本流放权 (Message) + 引用/组件分离流 (Citations Schema)
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        return chatOrchestrator.dispatchDynamicStream(sessionId, message);
    }

    /**
     * Scene 2 拓展: 纯结构化响应接口（为了支持不接 SSE，需一次拉取完整对象分析的客户端）
     * 发出即阻塞等待 LLM 吐出完整的 JSON Schema 合规对象 (RagStructuredResponse)
     */
    @PostMapping(value = "/chat/structured", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RagStructuredResponse> chatStructured(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        return Mono.fromCallable(() -> ragStructuredAgent.askStructured(sessionId, message))
                   .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
