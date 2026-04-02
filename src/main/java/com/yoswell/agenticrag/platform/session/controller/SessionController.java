package com.yoswell.agenticrag.platform.session.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.platform.session.dto.request.DeleteSessionRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.request.SessionCreateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.request.SessionListQueryRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.request.SessionMessageQueryRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.request.SessionUpdateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.response.SessionDetailsRespDTO;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.service.SessionContextSwitcher;  
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * 会话生命周期控制器
 *
 * <p>负责会话的创建、查询、激活、更新与删除等管理能力</p>
 * <p>所有接口默认在当前登录用户上下文下执行，Service 层会继续执行归属校验，
 * 防止跨用户会话越权访问</p>
 * <p>除流式接口外，本控制器统一返回 ApiResponse 作为业务响应封装</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final SessionContextSwitcher sessionSwitcher;

    /**
     * 创建会话
     *
     * @param request 会话创建参数（可为空，服务端使用默认策略）
     * @return 包含新建会话实体的统一响应
     */
    @PostMapping
    public ApiResponse<ChatSession> createSession(@RequestBody(required = false) SessionCreateRequestDTO request) {
        String userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(sessionService.createSession(userId, request));
    }

    /**
     * 分页查询当前用户会话列表
     *
     * @param reqDTO 查询参数（page/size/status）
     * @return 包含会话分页结果的统一响应
     */
    @GetMapping
    public ApiResponse<Page<ChatSession>> getSessions(@ModelAttribute SessionListQueryRequestDTO reqDTO) {
        String userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(sessionService.getSessions(
                userId,
                reqDTO.getStatus(),
                reqDTO.getPage(),
                reqDTO.getSize()));
    }

    /**
     * 查询指定会话消息详情
     *
     * @param sessionId 会话 ID
     * @param request 查询参数（page/size）
     * @return 包含会话详情与消息分页信息的统一响应
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<SessionDetailsRespDTO> getSessionMessages(
            @PathVariable String sessionId,
            @ModelAttribute SessionMessageQueryRequestDTO request) {
        String userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(sessionService.getSessionDetails(
                sessionId,
                userId,
                request.getPage(),
                request.getSize()));
    }

    /**
     * 激活并切换到目标会话
     *
     * @param sessionId 会话 ID
     * @return 包含激活后会话实体的统一响应
     */
    @PutMapping("/{sessionId}/activate")
    public ApiResponse<ChatSession> activateSession(@PathVariable String sessionId) {
        String userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(sessionSwitcher.activateSessionInRedis(sessionId, userId));
    }

    /**
     * 更新会话元信息
     *
     * @param sessionId 会话 ID
     * @param request 更新参数
     * @return 包含更新后会话实体的统一响应
     */
    @PatchMapping("/{sessionId}")
    public ApiResponse<ChatSession> updateSession(
            @PathVariable String sessionId,
            @RequestBody SessionUpdateRequestDTO request) {
        String userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(sessionService.updateSession(sessionId, userId, request));
    }

    /**
     * 删除或归档会话
     *
     * @param sessionId 会话 ID
     * @param request 查询参数（mode）
     * @return 统一响应（data 为 null）
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> deleteSession(
            @PathVariable String sessionId,
            @ModelAttribute DeleteSessionRequestDTO reqDTO) {
        String userId = SecurityUtils.getCurrentUserId();
        sessionService.deleteSession(sessionId, userId, reqDTO.getStatus());
        return ApiResponse.success(null);
    }
}
