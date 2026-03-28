package com.yoswell.agenticrag.core.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.core.agent.ai.RagStructuredAgent;
import com.yoswell.agenticrag.core.agent.dto.RagStructuredResponse;
import com.yoswell.agenticrag.core.agent.orchestrator.ChatOrchestrator;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.util.SecurityUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 核心大模型代理与编排(Orchestrator)控制器
 * 
 * Agent系统面向终端用户的推理大脑门面。
 * 提供两种交互形态支持：流式的沉浸 ReAct 问答 和 以系统为驱动向模型拉取 JSON 的强契约模式。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final ChatOrchestrator chatOrchestrator;
    private final RagStructuredAgent ragStructuredAgent;
    private final SessionService sessionService;

    public AgentController(ChatOrchestrator chatOrchestrator,
                           RagStructuredAgent ragStructuredAgent,
                           SessionService sessionService) {
        this.chatOrchestrator = chatOrchestrator;
        this.ragStructuredAgent = ragStructuredAgent;
        this.sessionService = sessionService;
    }
    /**
     * 发起基础的多轮增量流式对话(Streaming Conversation)
     *
     * 场景：最经典的类似 ChatGPT 用户输入页。该端点能够自适应底层大模型的 Function Calling 操作，向外
     * 动态混合推送不同的事件包，如 `event: tool_call`, `event: message`, `event: citations` 进而带动前端多模态呈现。
     *
     * @param sessionId X-Session-Id 头，关联这部分记忆处于Redis和LLM之间的哪一部分（必须）
     * @param message 用户新轮次的原始提问字符串（包含Prompt上下文要求等）
     * @return 响应式的服务器发送事件流(ServerSentEvent 流)
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        
        String userId = SecurityUtils.getCurrentUserId();
        sessionService.verifySessionAccess(sessionId, userId);
        return chatOrchestrator.dispatchDynamicStream(sessionId, message);
    }



    /**
     * 结构化格式化输出要求模式(Structural Data Generating)
     *
     * 场景：用户/外部程序并不是想要跟AI单纯地“聊天”，而可能是要求AI返回能够落库的数据结果集（JSON Schema强制约束等）。
     * 亦或者是基于指定上下文让其产出指定格式化的表单或特定的枚举分类，适用于下游系统进行系统集成与调用。
     * 它属于非阻塞单次请求直接结束调用链。
     *
     * @param sessionId 会话边界
     * @param message 让实体执行或者提供答案的问题
     * @return Mono格式封装的受Jackson与LLM绑定的强JSON格式响应对象 
     */
    @PostMapping(value = "/chat/structured", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RagStructuredResponse> chatStructured(
            @RequestHeader(value = "X-Session-Id", defaultValue = "default_session") String sessionId,
            @RequestBody String message) {
        
        String userId = SecurityUtils.getCurrentUserId();
        // Execute the pipeline explicitly capturing userId above to avoid SecurityContext loss
        return Mono.fromCallable(() -> {
            sessionService.verifySessionAccess(sessionId, userId);
            return ragStructuredAgent.askStructured(sessionId, message);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
