package com.yoswell.agenticrag.service.orchestrator;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.dto.Citation;
import com.yoswell.agenticrag.dto.IntentDecision;
import com.yoswell.agenticrag.service.agent.EnterpriseAgent;
import com.yoswell.agenticrag.service.agent.IntentRouterAgent;
import com.yoswell.agenticrag.service.session.ChatMessageService;

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
                    // Save User Message
                    chatMessageService.saveUserMessage(sessionId, message);

                    log.info("START: Scene 4A - Intent Routing via strict JSON Schema Constraint");
                    IntentDecision decision = intentRouterAgent.classify(message);
                    log.info("ROUTER DECIDED: intent={}, confidence={}", decision.intent(), decision.confidence());

                    // Give frontend UI an early heads up of the mode
                    sink.next(ServerSentEvent.builder(decision.intent()).event("intent_resolved").build());

                    if ("complex_plan".equals(decision.intent())) {
                        log.info("ROUTED TO: Scene 4B - Plan Generation");      
                        
                        // Wait for completion, then record task steps logically 
                        // Flux integration delegates to orchestrator
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
                        log.info("ROUTED TO: Scene 3 - Natural Language SSE (Markdown block bypasses DB structural binds)");
                        StringBuilder fullResponse = new StringBuilder();
                        TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                        tokenStream
                            .onNext(token -> {
                                fullResponse.append(token);
                                sink.next(ServerSentEvent.builder(token).event("message").build());
                            })
                            .onComplete(response -> {
                                List<Citation> citations = null;
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

    private List<Citation> emitMockCitationsWidget(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink) {
        List<Citation> citations = new ArrayList<>();
        try {
            citations.add(new Citation("doc-8899", "2025_Q3_Financial_Report.pdf", "chk-001", 0.92));
            String citationsJson = objectMapper.writeValueAsString(citations);  

            sink.next(ServerSentEvent.builder(citationsJson).event("citations").build());
        } catch (Exception e) {
            log.warn("Failed to generate formatting citations schema.", e);     
        }
        return citations;
    }
}