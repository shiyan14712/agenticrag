package com.yoswell.agenticrag.core.agent.orchestrator;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.ai.IntentRouterAgent;
import com.yoswell.agenticrag.core.agent.dto.CitationDto;
import com.yoswell.agenticrag.core.agent.dto.IntentDecision;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;

import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;

@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final IntentRouterAgent intentRouterAgent;
    private final EnterpriseAgent enterpriseAgent;
    private final PlanAndExecuteOrchestrator planAndExecuteOrchestrator;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;

    public ChatOrchestrator(IntentRouterAgent intentRouterAgent,
                            EnterpriseAgent enterpriseAgent,
                            PlanAndExecuteOrchestrator planAndExecuteOrchestrator,
                            ObjectMapper objectMapper,
                            ChatMessageService chatMessageService) {
        this.intentRouterAgent = intentRouterAgent;
        this.enterpriseAgent = enterpriseAgent;
        this.planAndExecuteOrchestrator = planAndExecuteOrchestrator;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
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

                    if ("complex_plan".equals(decision.intent())) {
                        log.info("ROUTED TO: Scene 4B - Plan Generation");
                        
                        StringBuilder fullResponse = new StringBuilder();
                        planAndExecuteOrchestrator.executeComplexTask(message)
                            .doOnNext(event -> {
                                sink.next(event);
                                if ("message".equals(event.event())) {
                                    fullResponse.append(event.data());
                                }
                            })
                            .doOnComplete(() -> {
                                chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), null);
                                sink.complete();
                            })
                            .doOnError(sink::error)
                            .subscribe();

                    } else {
                        log.info("ROUTED TO: Scene 3 - Natural Language SSE");
                        StringBuilder fullResponse = new StringBuilder();
                        TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                        tokenStream
                            .onNext(token -> {
                                fullResponse.append(token);
                                sink.next(ServerSentEvent.builder(token).event("message").build());
                            })
                            .onComplete(response -> {
                                List<CitationDto> citations = null;
                                if ("rag_search".equals(decision.intent())) {
                                    citations = emitMockCitationsWidget(sink);
                                }
                                chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                                sink.complete();
                            })
                            .onError(sink::error)
                            .start();
                    }
                } catch (Exception e) {
                    log.error("Error inside WebFlux Virtual Thread execution", e);
                    sink.error(e);
                }
            });
        });
    }

    // TODO: replace with actual retrieval and citation generation logic, this is just a mock implementation to demonstrate emitting a citations widget via SSE
    private List<CitationDto> emitMockCitationsWidget(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
        List<CitationDto> citations = new ArrayList<>();
        try {
            citations.add(new CitationDto("doc-8899", "2025_Q3_Financial_Report.pdf", "chk-001", 0.92));
            String citationsJson = objectMapper.writeValueAsString(citations);

            sink.next(ServerSentEvent.builder(citationsJson).event("citations").build());
        } catch (Exception e) {
            log.error("Error occurred while emitting mock citations widget", e);
        }
        return citations;
    }
}
