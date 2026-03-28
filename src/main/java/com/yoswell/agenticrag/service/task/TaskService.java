package com.yoswell.agenticrag.service.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.dto.ExecutionPlan;
import com.yoswell.agenticrag.dto.PlanStep;
import com.yoswell.agenticrag.service.agent.ExecutorAgent;
import com.yoswell.agenticrag.service.agent.PlannerAgent;
import com.yoswell.agenticrag.service.agent.SynthesizerAgent;

import reactor.core.publisher.Flux;

/**
 * 异步任务生命周期管理器。
 * 管理 Plan-and-Execute 模式下的长时运行任务，支持：
 * - 任务提交和异步启动
 * - SSE 事件流挂载（支持断线重连）
 * - 全量状态快照拉取
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final SynthesizerAgent synthesizerAgent;
    private final ObjectMapper objectMapper;

    /**
     * 任务注册表，持有所有活跃/已完成的任务上下文
     * 生产环境应考虑过期清理机制（可配合 ScheduledExecutor 或 Caffeine Cache）
     */
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

    /**
     * 提交长时运行任务。立即返回 TaskContext（含 taskId），
     * 后台 Virtual Thread 异步执行 Plan-and-Execute 全流程。
     */
    public TaskContext submitTask(String taskId, String objective, String userId) {
        TaskContext context = new TaskContext(taskId, objective, userId);
        taskRegistry.put(taskId, context);

        log.info("Task submitted: taskId={}, objective={}", taskId, objective);

        // 使用 JDK Virtual Thread 异步执行，不阻塞 Netty 事件循环
        Thread.startVirtualThread(() -> executeTask(context));

        return context;
    }

    /**
     * 获取任务的 SSE 事件流。
     * 基于 Sinks.Many replay 模式，新订阅者可接收历史事件（支持断线重连）。
     */
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

    /**
     * 拉取任务计划步骤的全量状态快照（供短轮询或断线重连后状态恢复）
     */
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

    /**
     * Plan-and-Execute 全流程执行逻辑（在 Virtual Thread 中运行）
     */
    private void executeTask(TaskContext context) {
        var sink = context.getEventSink();

        try {
            // Phase 1: Planning
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

            // Phase 2: Execution
            context.setStatus("EXECUTING");
            StringBuilder executionContext = new StringBuilder();

            for (PlanStep step : context.getSteps()) {
                log.info("Task [{}] executing step [{}]: {}", context.getTaskId(), step.getId(), step.getDescription());

                context.updateStepStatus(step.getId(), "IN_PROGRESS");
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("tool_call")
                        .data("Executing: " + step.getDescription())
                        .build());

                // 推送步骤状态更新给前端
                String updatedStepsJson = objectMapper.writeValueAsString(context.getSteps());
                sink.tryEmitNext(ServerSentEvent.<String>builder().event("plan_steps").data(updatedStepsJson).build());

                String result = executorAgent.executeStep(step.getDescription(), executionContext.toString());

                executionContext.append("Step ID: ").append(step.getId())
                        .append(" | Description: ").append(step.getDescription()).append("\n")
                        .append("Result: ").append(result).append("\n\n");

                context.updateStepStatus(step.getId(), "DONE");
                log.info("Task [{}] step [{}] completed", context.getTaskId(), step.getId());
            }

            // Phase 3: Synthesis
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
