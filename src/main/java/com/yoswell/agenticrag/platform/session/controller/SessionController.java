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
import com.yoswell.agenticrag.platform.session.dto.SessionCreateRequest;
import com.yoswell.agenticrag.platform.session.dto.SessionUpdateRequest;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.service.SessionContextSwitcher;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.util.SecurityUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 会话(Session)生命周期管理控制器
 * 
 * 负责智能体对话会话的创建、查询、历史记录拉取、切换上下文及归档操作。
 * 严格基于当前登录用户的 UserID 进行多租户级别的资源隔离，避免串号。
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
     * 创建全新对话会话 (New Session)
     *
     * 场景：用户点击左侧边栏的“新对话”按钮时调用。创建一个干净、未受上下文污染的独立会话空间。
     * 
     * @param request 包含可选的模型参数(例如: modelId)等配置信息
     * @return 初始化的会话实体对象
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ChatSession> createSession(@RequestBody(required = false) SessionCreateRequest request) {
        return Mono.fromCallable(() -> sessionService.createSession(SecurityUtils.getCurrentUserId(), request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页查询用户的会话列表
     *
     * 场景：用户打开页面，渲染左侧边栏此前的历史话题列表，通常按最后活跃时间降序。
     *
     * @param page 页码，从 0 开始，默认为 0
     * @param size 每页拉取数量，默认为 20
     * @param status 会话状态（如：ACTIVE, ARCHIVED），默认为 ACTIVE
     * @return 分页装载的 ChatSession 数据对象
     */
    @GetMapping
    public Mono<Page<ChatSession>> getSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        return Mono.fromCallable(() -> sessionService.getSessions(SecurityUtils.getCurrentUserId(), status, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取指定会话的历史消息记录
     *
     * 场景：用户在左侧边栏点击了过往的某个话题，进入主界面需要展现该该话题完整的聊天记录上下文。
     * 
     * @param sessionId 唯一会话 ID
     * @param page 页码，从 0 开始，默认为 0
     * @param size 每页载入记录数，为了沉浸式体验这里默认可设置稍大（如 50）
     * @return 包含当前 Session 基础元数据和历史消息分页数据的聚合 Map
     */
    @GetMapping("/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        String userId = SecurityUtils.getCurrentUserId();
        return Mono.fromCallable(() -> sessionService.getSessionDetails(sessionId, userId, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 切换/激活目标会话成为当前活跃上下文
     *
     * 场景：由于Agent可能会将活跃状态维持在 Redis 以优化速度，当用户在不同的话题间频繁跳转时，
     * 利用此接口将目标会话推入热数据层（预热L1内存）。
     *
     * @param sessionId 唯将要激活的目标会话 ID
     * @return 已经切换状态完成的 ChatSession 对象
     */
    @PutMapping("/{sessionId}/activate")
    public Mono<ChatSession> activateSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> sessionSwitcher.activateSession(sessionId, SecurityUtils.getCurrentUserId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 更新指定会话属性 
     *
     * 场景：LLM 或用户自己重命名了当前的主题名称，或修改了系统设定的偏好参数。
     *
     * @param sessionId 指定待更新的会话 ID
     * @param request 需要更新的字段定义(如 : title 等)
     * @return 最新的 Session 数据
     */
    @PatchMapping("/{sessionId}")
    public Mono<ChatSession> updateSession(
            @PathVariable String sessionId,
            @RequestBody SessionUpdateRequest request) {
        return Mono.fromCallable(() -> sessionService.updateSession(sessionId, SecurityUtils.getCurrentUserId(), request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 归档或删除会话
     *
     * 场景：用户不希望这个话题再出现在前端列表。支持逻辑归档而非物理硬删除（按模式调整）。
     *
     * @param sessionId 选择删除的会话 ID
     * @param mode 操作模式，例如 'archive'（默认）仅标记不删除归档；'hard_delete' 代表级联彻底清除
     * @return Mono.empty() 代表 204 No Content 执行成功
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "archive") String mode) {
        return Mono.<Void>fromRunnable(() -> {
            sessionService.deleteSession(sessionId, SecurityUtils.getCurrentUserId(), mode);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
