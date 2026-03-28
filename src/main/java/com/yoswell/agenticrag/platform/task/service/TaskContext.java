package com.yoswell.agenticrag.platform.task.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.codec.ServerSentEvent;

import com.yoswell.agenticrag.core.agent.dto.PlanStep;

import reactor.core.publisher.Sinks;

public class TaskContext {

    private final String taskId;
    private final String objective;
    private final String userId;
    private final LocalDateTime createdAt;

    private volatile String status;
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

    public void updateStepStatus(String stepId, String newStatus) {
        for (PlanStep step : steps) {
            if (stepId.equals(step.getId())) {
                step.setStatus(newStatus);
                break;
            }
        }
    }
}
