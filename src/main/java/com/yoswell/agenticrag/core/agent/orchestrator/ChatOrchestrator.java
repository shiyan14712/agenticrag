package com.yoswell.agenticrag.core.agent.orchestrator;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.ai.IntentRouterAgent;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDto;
import com.yoswell.agenticrag.core.agent.dto.IntentDecision;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResult;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;

import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final IntentRouterAgent intentRouterAgent;
    private final EnterpriseAgent enterpriseAgent;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    public ChatOrchestrator(IntentRouterAgent intentRouterAgent,
                            EnterpriseAgent enterpriseAgent,
                            ObjectMapper objectMapper,
                            ChatMessageService chatMessageService,
                            RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.intentRouterAgent = intentRouterAgent;
        this.enterpriseAgent = enterpriseAgent;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    public Flux<ServerSentEvent<String>> dispatchDynamicStream(String sessionId, String message) {
        return Flux.create(sink -> {
            Thread.startVirtualThread(() -> {
                try {
                    chatMessageService.saveUserMessage(sessionId, message);

                    log.info("START: Scene 4A - Intent Routing via strict JSON Schema Constraint");
                    IntentDecision decision = intentRouterAgent.classify(message);
                    log.info("ROUTER DECIDED: intent={}, confidence={}", decision.intent(), decision.confidence());

                    sink.next(ServerSentEvent.builder(decision.intent()).event("intent_resolved").build());


                        log.info("ROUTED TO: Scene 3 - Natural Language SSE");
                        StringBuilder fullResponse = new StringBuilder();
                        AutoCloseable retrievalScope = ragRetrievalContextHolder.bindSession(sessionId);
                        TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                        tokenStream
                            .onNext(token -> {
                                fullResponse.append(token);
                                sink.next(ServerSentEvent.builder(token).event("message").build());
                            })
                            .onComplete(response -> {
                                List<CitationDto> citations = List.of();
                                try {
                                    if ("rag_search".equals(decision.intent())) {
                                        citations = ragRetrievalContextHolder.consume(sessionId)
                                                .map(RagSearchResult::citations)
                                                .orElse(List.of());
                                        emitCitationsWidget(sink, citations);
                                    }
                                } finally {
                                    closeQuietly(retrievalScope);
                                }
                                chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                                sink.complete();
                            })
                            .onError(error -> {
                                closeQuietly(retrievalScope);
                                sink.error(error);
                            })
                            .start();
                } catch (Exception e) {
                    log.error("Error inside WebFlux Virtual Thread execution", e);
                    sink.error(e);
                }
            });
        });
    }

    private void emitCitationsWidget(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                     List<CitationDto> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        try {
            String citationsJson = objectMapper.writeValueAsString(citations);
            sink.next(ServerSentEvent.builder(citationsJson).event("citations").build());
        } catch (Exception e) {
            log.error("Error occurred while emitting citations widget", e);
        }
    }

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
}
