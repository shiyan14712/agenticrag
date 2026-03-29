package com.yoswell.agenticrag.platform.session.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.platform.session.dto.SessionCreateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.SessionUpdateRequestDTO;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.service.SessionContextSwitcher;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 聊天会话管理控制器
 *
 * <p>负责对话会话 (Chat Session) 的全生命周期管理，包括创建、查询、激活、更新和删除。
 * 所有的端点均基于 WebFlux 响应式框架，通过 ReactiveSecurityContext 提取当前用身份，
 * 采用异步调度模式将数据库持久化等潜在阻塞操作指派到 boundedElastic 线程池运行。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionContextSwitcher sessionSwitcher;

    public SessionController(SessionService sessionService, SessionContextSwitcher sessionSwitcher) {
        this.sessionService = sessionService;
        this.sessionSwitcher = sessionSwitcher;
    }

    /**
     * 创建新的聊天会话
     *
     * @param request 包含会话初始参数的数据传输对象（例如初始提示词、预设标题）可选
     * @return 已持久化的聊天会话实体对象
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ChatSession> createSession(@RequestBody(required = false) SessionCreateRequestDTO request) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> sessionService.createSession(userId, request))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 分页查询当前用户下归属的聊天会话列表
     *
     * @param page   分页页码（默认 0 起始）
     * @param size   分页大小（默认每页 20 条）
     * @param status 要查询的会话状态过滤器（默认检索 ACTIVE 状态的会话）
     * @return 聊天会话分页数据 (MyBatis-Plus 分页模型)
     */
    @GetMapping
    public Mono<Page<ChatSession>> getSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> sessionService.getSessions(userId, status, page, size))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 分页获取指定聊天会话内部的历史消息记录
     *
     * @param sessionId 会话的唯一 ID
     * @param page      分页页码（默认 0 起始）
     * @param size      记录每页大小（默认每页 50 条消息）
     * @return 包含当前会话消息上下文数据的 Map，通常包含关联联接的信息列表
     */
    @GetMapping("/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> sessionService.getSessionDetails(sessionId, userId, page, size))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 激活并切换至指定会话
     *
     * <p>恢复或者切入某个特定会话环境时被客户端调用，会验证用户的归属权。</p>
     *
     * @param sessionId 需激活的会话 ID
     * @return 激活后的最新会话实体对象
     */
    @PutMapping("/{sessionId}/activate")
    public Mono<ChatSession> activateSession(@PathVariable String sessionId) {  
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> sessionSwitcher.activateSession(sessionId, userId))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 更新指定会话的信息（如：重命名会话标题等操作）
     *
     * @param sessionId 会话唯一 ID
     * @param request   包含增量更新字段的 DTO
     * @return 更新成功后的会话实体对象
     */
    @PatchMapping("/{sessionId}")
    public Mono<ChatSession> updateSession(
            @PathVariable String sessionId,
            @RequestBody SessionUpdateRequestDTO request) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.fromCallable(() -> sessionService.updateSession(sessionId, userId, request))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 删除指定的聊天会话
     *
     * <p>系统默认行为可能是软删除或归档，可通过 mode 参数变更。</p>
     *
     * @param sessionId 欲删除的会话 ID
     * @param mode      操作模式（默认 archive: 归档; 否则按物理删除或逻辑删除分支）
     * @return 响应式的 Void 完成信号（HTTP 204）
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "archive") String mode) {
        return SecurityUtils.getCurrentUserId()
                .flatMap(userId -> Mono.<Void>fromRunnable(() -> {
                    sessionService.deleteSession(sessionId, userId, mode);      
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
