package com.yoswell.agenticrag.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.model.Citation;
import com.yoswell.agenticrag.model.IntentDecision;

import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;

@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    
    private final IntentRouterAgent intentRouterAgent;
    private final EnterpriseAgent enterpriseAgent;
    private final PlanAndExecuteOrchestrator planAndExecuteOrchestrator;
    private final ObjectMapper objectMapper;

    public ChatOrchestrator(IntentRouterAgent intentRouterAgent,
                            EnterpriseAgent enterpriseAgent,
                            PlanAndExecuteOrchestrator planAndExecuteOrchestrator,
                            ObjectMapper objectMapper) {
        this.intentRouterAgent = intentRouterAgent;
        this.enterpriseAgent = enterpriseAgent;
        this.planAndExecuteOrchestrator = planAndExecuteOrchestrator;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> dispatchDynamicStream(String sessionId, String message) {
        return Flux.create(sink -> {
            Thread.startVirtualThread(() -> {
                try {
                    log.info("START: Scene 4A - Intent Routing via strict JSON Schema Constraint");
                    IntentDecision decision = intentRouterAgent.classify(message);
                    log.info("ROUTER DECIDED: intent={}, confidence={}", decision.intent(), decision.confidence());
                    
                    // Give frontend UI an early heads up of the mode
                    sink.next(ServerSentEvent.builder(decision.intent()).event("intent_resolved").build());

                    if ("complex_plan".equals(decision.intent())) {
                        log.info("ROUTED TO: Scene 4B - Plan Generation");
                        // Flux integration delegates to orchestrator
                        planAndExecuteOrchestrator.executeComplexTask(message)
                            .doOnNext(sink::next)
                            .doOnComplete(sink::complete)
                            .doOnError(sink::error)
                            .subscribe();
                            
                    } else {
                        log.info("ROUTED TO: Scene 3 - Natural Language SSE (Markdown block bypasses DB structural binds)");
                        TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                        tokenStream
                            .onNext(token -> sink.next(ServerSentEvent.builder(token).event("message").build()))
                            .onComplete(response -> {
                                if ("rag_search".equals(decision.intent())) {
                                    // Scene 2: Structured Output for Widgets - Sent purely as JSON blocks at end 
                                    emitMockCitationsWidget(sink);
                                }
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

    private void emitMockCitationsWidget(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
        try {
            List<Citation> citations = new ArrayList<>();
            citations.add(new Citation("doc-8899", "2025_Q3_Financial_Report.pdf", "chk-001", 0.92));
            String citationsJson = objectMapper.writeValueAsString(citations);
            
            sink.next(ServerSentEvent.builder(citationsJson).event("citations").build());
        } catch (Exception e) {
            log.warn("Failed to generate formatting citations schema.", e);
        }
    }
}
