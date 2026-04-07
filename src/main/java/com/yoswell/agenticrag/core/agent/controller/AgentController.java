package com.yoswell.agenticrag.core.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.core.agent.ai.RagStructuredAgent;
import com.yoswell.agenticrag.core.agent.dto.RagStructuredResponseDTO;
import com.yoswell.agenticrag.core.agent.service.ChatService;
import com.yoswell.agenticrag.core.agent.service.orchestrator.ChatOrchestrator;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Agent 对话控制器
 *
 * <p>负责承接前端与智能体的交互请求，按场景分为：</p>
 * <p>1) SSE 流式对话（保留原生流式返回）；</p>
 * <p>2) 结构化问答与标题相关接口（统一返回 ApiResponse 包装）</p>
 * <p>所有接口在进入核心业务前均执行会话访问校验，确保会话归属与安全边界</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final ChatOrchestrator chatOrchestrator;
    private final RagStructuredAgent ragStructuredAgent;
    private final SessionService sessionService;
    private final ChatService chatService;

    /**
     * 发起流式对话
     *
     * <p>该接口属于流式输出场景，返回 {@link SseEmitter}，不使用 ApiResponse 包装</p>
     * <p>进入编排器前会校验当前用户是否有权访问目标会话</p>
     *
     * @param sessionId 会话 ID（来源于请求头 X-Session-Id）
     * @param message 用户输入消息
     * @return SSE 流式响应发射器
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody String message) {
        String userId = SecurityUtils.getCurrentUserId();
        sessionService.verifySessionAccess(sessionId, userId);
        return chatOrchestrator.dispatchDynamicStream(sessionId, message);
    }

    /**
     * 发起结构化问答
     *
     * <p>用于一次性返回结构化结果，遵循统一响应契约并包装为 ApiResponse</p>
     *
     * @param sessionId 会话 ID（来源于请求头 X-Session-Id）
     * @param message 用户输入消息
     * @return 包含结构化问答结果的统一响应
     */
    @PostMapping(value = "/chat/structured", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RagStructuredResponseDTO> chatStructured(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody String message) {
        String userId = SecurityUtils.getCurrentUserId();
        sessionService.verifySessionAccess(sessionId, userId);
        return ApiResponse.success(ragStructuredAgent.askStructured(sessionId, message));
    }

    /**
     * 生成会话标题候选
     *
     * <p>根据用户输入生成简洁标题，返回统一响应结构</p>
     *
     * @param userQuery 用户输入文本
     * @return 包含标题文本的统一响应
     */
    @PostMapping("/title")
    public ApiResponse<String> getSessionTitle(@RequestBody String userQuery) {
        return ApiResponse.success(chatService.generateTitle(userQuery));
    }

    /**
     * 查询会话已生成标题
     *
     * @param sessionId 会话 ID
     * @return 包含会话标题的统一响应
     */
    @GetMapping("/title/{sessionId}")
    public ApiResponse<String> getGeneratedTitle(@PathVariable String sessionId) {
        String userId = SecurityUtils.getCurrentUserId();
        sessionService.verifySessionAccess(sessionId, userId);
        return ApiResponse.success(chatService.getSessionTitle(sessionId, userId));
    }
}
