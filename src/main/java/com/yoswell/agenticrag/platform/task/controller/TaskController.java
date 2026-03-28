package com.yoswell.agenticrag.platform.task.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.util.SecurityUtils;
import com.yoswell.agenticrag.platform.task.dto.TaskSubmitRequest;
import com.yoswell.agenticrag.platform.task.service.TaskContext;
import com.yoswell.agenticrag.platform.task.service.TaskService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 复杂任务(Plan-and-Execute)编排控制器
 * 
 * 为 Agent 面对需要深度思考与多步执行的宏大任务（宏观目标执行模式）提供异步请求和响应端点。
 * 支持长时间执行任务的创建提交、步骤流（Todos）监听、以及各个阶段的快照拉取。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交一个新的异步执行任务
     * 
     * 场景：用户输入了一个复杂请求，例如“对比并输出2024年和2025年财报的核心差异报告”。
     * 后端将基于这个大目标生成多条执行路径，并立即返回任务句柄，不阻塞连接。
     *
     * @param request 任务请求体对象，包装了任务主要内容(例如 objective)
     * @return Accepted响应(202)，包含已生成的 taskId 和初始状态，供前端后续挂载 SSE
     */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Mono<Map<String, String>> submitTask(@RequestBody TaskSubmitRequest request) {
        if (request == null || request.getObjective() == null || request.getObjective().trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Task objective cannot be empty"));
        }

        // Extract user context before entering the reactive pipeline's worker thread to prevent SecurityContext loss
        String userId = SecurityUtils.getCurrentUserId();

        return Mono.fromCallable(() -> {
            TaskContext context = taskService.submitTask(request.getObjective(), userId);

            return Map.of(
                "taskId", context.getTaskId(),
                "status", context.getStatus(),
                "objective", request.getObjective()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 建立服务器发送事件(SSE)单向流，监听任务全生命周期
     * 
     * 场景：前端获取到 `taskId` 后立刻调用此接口，接收后续 Agent 不断推过来的执行状态。
     * 如：Plan Steps事件(创建骨架)、Tool calls(工具运转)、Message事件(产出结果流段)。
     *
     * @param taskId 目标执行在运行中的任务ID
     * @return ServerSentEvent 流，推送到前端由不同类型解析器消费
     */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> taskEventsStream(@PathVariable String taskId) {
        return taskService.getTaskEventStream(taskId);
    }

    /**
     * 获取任务下的计划与完成快照（子任务列表 / Todos）
     * 
     * 场景：网络波动/页面被重刷新导致 SSE 监听中断时，或者仅仅基于被动触发的补偿查询。
     * 前端利用此 API 将当前 Agent 中所处的状态进度条直接恢复出来渲染。
     *
     * @param taskId 需查询快照的目标任务ID
     * @return JSON结构的复杂任务拆解对象，主要包含 steps[] 中的多阶段处理标志（如：`PENDING`, `IN_PROGRESS`, `COMPLETED`）
     */
    @GetMapping(value = "/{taskId}/todos", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> getTaskTodosSnapshot(@PathVariable String taskId) {
        return Mono.fromCallable(() -> taskService.getTaskSnapshot(taskId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
