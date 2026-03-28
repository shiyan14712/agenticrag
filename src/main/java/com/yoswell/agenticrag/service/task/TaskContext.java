package com.yoswell.agenticrag.service.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.yoswell.agenticrag.dto.PlanStep;

import reactor.core.publisher.Sinks;

import org.springframework.http.codec.ServerSentEvent;

/**
 * 任务上下文持有者。
 * 持有每个长时运行 Plan-and-Execute 任务的运行时状态，
 * 包含 Reactor Sinks.Many 作为多订阅者事件广播通道（支持断线重连）。
 */
public class TaskContext {

    private final String taskId;
    private final String objective;
    private final String userId;
    private final LocalDateTime createdAt;

    private volatile String status; // SUBMITTED, PLANNING, EXECUTING, SYNTHESIZING, COMPLETED, FAILED
    private final List<PlanStep> steps;
    private final Sinks.Many<ServerSentEvent<String>> eventSink;
    private final StringBuilder fullResponse;

    public TaskContext(String taskId, String objective, String userId) {
        this.taskId = taskId;
        this.objective = objective;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.status = "SUBMITTED";
        this.steps = new CopyOnWriteArrayList<>();
        // replay() 模式：新订阅者可获取历史事件，支持断线重连场景
        this.eventSink = Sinks.many().replay().all();
        this.fullResponse = new StringBuilder();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getObjective() {
        return objective;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<PlanStep> getSteps() {
        return steps;
    }

    public Sinks.Many<ServerSentEvent<String>> getEventSink() {
        return eventSink;
    }

    public StringBuilder getFullResponse() {
        return fullResponse;
    }

    /**
     * 更新指定步骤的状态
     */
    public void updateStepStatus(String stepId, String newStatus) {
        for (PlanStep step : steps) {
            if (stepId.equals(step.getId())) {
                step.setStatus(newStatus);
                break;
            }
        }
    }
}
