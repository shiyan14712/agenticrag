package com.yoswell.agenticrag.platform.task.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.web.security.model.TenantUser;
import com.yoswell.agenticrag.platform.task.dto.TaskSubmitRequest;
import com.yoswell.agenticrag.platform.task.service.TaskContext;
import com.yoswell.agenticrag.platform.task.service.TaskService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public Mono<Map<String, String>> submitTask(@RequestBody TaskSubmitRequest request) {
        return Mono.fromCallable(() -> {
            String taskId = UUID.randomUUID().toString();
            String userId = getCurrentUserId();

            TaskContext context = taskService.submitTask(taskId, request.getObjective(), userId);

            return Map.of(
                "taskId", context.getTaskId(),
                "status", context.getStatus(),
                "objective", request.getObjective()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> taskEventsStream(@PathVariable String taskId) {
        return taskService.getTaskEventStream(taskId);
    }

    @GetMapping(value = "/{taskId}/todos", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> getTaskTodosSnapshot(@PathVariable String taskId) {
        return Mono.fromCallable(() -> taskService.getTaskSnapshot(taskId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser.getUserId();
        }
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
