package com.yoswell.agenticrag.core.agent.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.core.agent.ai.ExecutorAgent;
import com.yoswell.agenticrag.core.agent.ai.PlannerAgent;
import com.yoswell.agenticrag.core.agent.ai.SynthesizerAgent;
import com.yoswell.agenticrag.core.agent.dto.ExecutionPlan;
import com.yoswell.agenticrag.core.agent.dto.PlanStep;

import reactor.core.publisher.Flux;

@Service
public class PlanAndExecuteOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteOrchestrator.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final SynthesizerAgent synthesizerAgent;
    private final ObjectMapper objectMapper;

    public PlanAndExecuteOrchestrator(PlannerAgent plannerAgent,
                                      ExecutorAgent executorAgent,
                                      SynthesizerAgent synthesizerAgent,
                                      ObjectMapper objectMapper) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.synthesizerAgent = synthesizerAgent;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> executeComplexTask(String userMessage) {
        return Flux.create(sink -> {
            Thread.startVirtualThread(() -> {
                try {
                    log.info("Starting Plan-and-Execute workflow for message: {}", userMessage);
                    
                    ExecutionPlan plan = plannerAgent.generatePlan(userMessage);

                    if (plan.getSteps() != null) {
                        for (PlanStep step : plan.getSteps()) {
                            step.setStatus("PENDING");
                        }
                    }

                    String planJson = objectMapper.writeValueAsString(plan.getSteps());
                    
                    log.info("Plan generated: {}", planJson);
                    sink.next(ServerSentEvent.builder(planJson).event("plan_steps").build());

                    StringBuilder executionContext = new StringBuilder();
                    
                    if (plan.getSteps() != null) {
                        for (PlanStep step : plan.getSteps()) {
                            log.info("Executing step [{}] {}", step.getId(), step.getDescription());
                            
                            step.setStatus("IN_PROGRESS");

                            sink.next(ServerSentEvent.builder("Executing Task: " + step.getDescription()).event("tool_call").build());
                            
                            String result = executorAgent.executeStep(step.getDescription(), executionContext.toString());
                            
                            executionContext.append("Step ID: ").append(step.getId())
                                    .append(" | Description: ").append(step.getDescription()).append("\n")
                                    .append("Result: ").append(result).append("\n\n");

                            step.setStatus("DONE");
                        }
                    }

                    log.info("Synthesizing final response output");
                    sink.next(ServerSentEvent.builder("Synthesizing final report...").event("tool_call").build());
                    
                    synthesizerAgent.synthesize(userMessage, executionContext.toString())
                            .onNext(token -> sink.next(ServerSentEvent.builder(token).event("message").build()))
                            .onComplete(response -> sink.complete())
                            .onError(sink::error)
                            .start();

                } catch (Exception e) {
                    log.error("Error during Plan-and-Execute workflow", e);
                    sink.error(e);
                }
            });
        });
    }
}
