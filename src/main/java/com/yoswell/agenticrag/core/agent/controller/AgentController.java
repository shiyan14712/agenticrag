package com.yoswell.agenticrag.core.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.core.agent.ai.RagStructuredAgent;
import com.yoswell.agenticrag.core.agent.dto.RagStructuredResponse;
import com.yoswell.agenticrag.core.agent.orchestrator.ChatOrchestrator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ChatOrchestrator chatOrchestrator;
    private final RagStructuredAgent ragStructuredAgent;

    public AgentController(ChatOrchestrator chatOrchestrator, RagStructuredAgent ragStructuredAgent) {
        this.chatOrchestrator = chatOrchestrator;
        this.ragStructuredAgent = ragStructuredAgent;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        return chatOrchestrator.dispatchDynamicStream(sessionId, message);
    }

    @PostMapping(value = "/chat/structured", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RagStructuredResponse> chatStructured(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        return Mono.fromCallable(() -> ragStructuredAgent.askStructured(sessionId, message))
                   .subscribeOn(Schedulers.boundedElastic());
    }
}
