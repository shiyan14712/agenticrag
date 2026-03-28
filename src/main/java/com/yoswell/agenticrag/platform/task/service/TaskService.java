package com.yoswell.agenticrag.platform.task.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final SynthesizerAgent synthesizerAgent;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, TaskContext> taskRegistry = new ConcurrentHashMap<>();

    public TaskService(PlannerAgent plannerAgent,
                       ExecutorAgent executorAgent,
                       SynthesizerAgent synthesizerAgent,
                       ObjectMapper objectMapper) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.synthesizerAgent = synthesizerAgent;
        this.objectMapper = objectMapper;
    }

    public TaskContext submitTask(String objective, String userId) {
        String taskId = java.util.UUID.randomUUID().toString();
        TaskContext context = new TaskContext(taskId, objective, userId);
        taskRegistry.put(taskId, context);

        log.info("Submitting new Plan-and-Execute task: taskId={}, userId={}, objective='{}'", 
                 taskId, userId, objective);

        Thread.startVirtualThread(() -> executeTask(context));

        return context;
    }

    public Flux<ServerSentEvent<String>> getTaskEventStream(String taskId) {
        TaskContext context = taskRegistry.get(taskId);
        if (context == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("Task not found: " + taskId)
                    .build());
        }
        return context.getEventSink().asFlux();
    }

    public Map<String, Object> getTaskSnapshot(String taskId) {
        TaskContext context = taskRegistry.get(taskId);
        if (context == null) {
            return Map.of("error", "Task not found: " + taskId);
        }

        List<Map<String, Object>> stepSnapshots = new ArrayList<>();
        for (PlanStep step : context.getSteps()) {
            stepSnapshots.add(Map.of(
                "stepId", step.getId(),
                "description", step.getDescription(),
                "status", step.getStatus() != null ? step.getStatus() : "PENDING"
            ));
        }

        return Map.of(
            "taskId", context.getTaskId(),
            "objective", context.getObjective(),
            "status", context.getStatus(),
            "steps", stepSnapshots
        );
    }

    private void executeTask(TaskContext context) {
        var sink = context.getEventSink();

        try {
            context.setStatus("PLANNING");
            log.info("Task [{}] entering planning phase", context.getTaskId());

            ExecutionPlan plan = plannerAgent.generatePlan(context.getObjective());

            if (plan.getSteps() != null) {
                for (PlanStep step : plan.getSteps()) {
                    step.setStatus("PENDING");
                }
                context.getSteps().addAll(plan.getSteps());
            }

            String planJson = objectMapper.writeValueAsString(context.getSteps());
            log.info("Task [{}] plan generated: {}", context.getTaskId(), planJson);
            sink.tryEmitNext(ServerSentEvent.<String>builder().event("plan_steps").data(planJson).build());

            context.setStatus("EXECUTING");
            StringBuilder executionContext = new StringBuilder();

            for (PlanStep step : context.getSteps()) {
                log.info("Task [{}] executing step [{}]: {}", context.getTaskId(), step.getId(), step.getDescription());

                context.updateStepStatus(step.getId(), "IN_PROGRESS");
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("tool_call")
                        .data("Executing: " + step.getDescription())
                        .build());

                String updatedStepsJson = objectMapper.writeValueAsString(context.getSteps());
                sink.tryEmitNext(ServerSentEvent.<String>builder().event("plan_steps").data(updatedStepsJson).build());

                String result = executorAgent.executeStep(step.getDescription(), executionContext.toString());

                executionContext.append("Step ID: ").append(step.getId())
                        .append(" | Description: ").append(step.getDescription()).append("\n")
                        .append("Result: ").append(result).append("\n\n");

                context.updateStepStatus(step.getId(), "DONE");
                log.info("Task [{}] step [{}] completed", context.getTaskId(), step.getId());
            }

            context.setStatus("SYNTHESIZING");
            log.info("Task [{}] entering synthesis phase", context.getTaskId());
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event("tool_call")
                    .data("Synthesizing final report...")
                    .build());

            synthesizerAgent.synthesize(context.getObjective(), executionContext.toString())
                    .onNext(token -> {
                        context.getFullResponse().append(token);
                        sink.tryEmitNext(ServerSentEvent.<String>builder().event("message").data(token).build());
                    })
                    .onComplete(response -> {
                        context.setStatus("COMPLETED");
                        log.info("Task [{}] completed successfully", context.getTaskId());
                        sink.tryEmitComplete();
                    })
                    .onError(error -> {
                        context.setStatus("FAILED");
                        log.error("Task [{}] synthesis failed", context.getTaskId(), error);
                        sink.tryEmitNext(ServerSentEvent.<String>builder().event("error").data(error.getMessage()).build());
                        sink.tryEmitComplete();
                    })
                    .start();

        } catch (Exception e) {
            context.setStatus("FAILED");
            log.error("Task [{}] execution failed", context.getTaskId(), e);
            sink.tryEmitNext(ServerSentEvent.<String>builder().event("error").data(e.getMessage()).build());
            sink.tryEmitComplete();
        }
    }
}
