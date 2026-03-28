package com.yoswell.agenticrag.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent/task")
public class TaskController {

    // 实际应注入 TaskService 或 PlanAndExecuteOrchestrator
    // private final PlanAndExecuteOrchestrator planOrchestrator;

    /**
     * 提交长时运行的 Plan-and-Execute 复杂任务，立即返回 taskId
     */
    @PostMapping
    public Mono<Map<String, String>> submitTask(@RequestBody String objective) {
        String taskId = UUID.randomUUID().toString();
        // TODO: implement real plan-and-execution logics

        // 底层服务异步开始拆解任务图
        // planOrchestrator.startAsync(taskId, objective);
        return Mono.just(Map.of("taskId", taskId, "status", "SUBMITTED", "objective", objective));
    }

    /**
     * 前端/客户端重新挂载或持续监听长期任务执行进展（SSE）
     */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> taskEventsStream(@PathVariable String taskId) {
        // TODO: implement real plan-and-execution logics

        // planOrchestrator.attachToTask(taskId);
        // 此处返回 Flux 流包含 event: plan_steps, tool_call 等
        return Flux.just(ServerSentEvent.<String>builder()
                .event("plan_steps")
                .data("[{\"step\": 1, \"status\": \"in-progress\"}]")
                .build());
    }

    /**
     * 为支持短轮询或断线重连，提供拉取当前计划树全量状态机的接口
     */
    @GetMapping(value = "/{taskId}/todos", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<Map<String, Object>>> getTaskTodosSnapshot(@PathVariable String taskId) {
        // TODO: implement real plan-and-execution logics

        // planOrchestrator.getPlanSnapshot(taskId);
        return Mono.just(List.of(
            Map.of("stepId", "1", "description", "Analyze requirements", "status", "DONE"),
            Map.of("stepId", "2", "description", "Fetch user data from DB", "status", "IN_PROGRESS"),
            Map.of("stepId", "3", "description", "Generate final report", "status", "PENDING")
        ));
    }
}
