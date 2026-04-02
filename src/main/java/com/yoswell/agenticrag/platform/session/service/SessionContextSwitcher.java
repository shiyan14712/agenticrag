package com.yoswell.agenticrag.platform.session.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.event.SessionSwitchedEvent;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

/**
 * 会话上下文切换服务
 *
 * <p>负责将用户活跃会话指针切换到目标会话，并发布切换事件与触发异步预热</p>
 */
@Service
public class SessionContextSwitcher {

    private final SessionRedisManager redisManager;
    private final ChatSessionMapper sessionMapper;
    private final ApplicationEventPublisher eventPublisher;

    public SessionContextSwitcher(SessionRedisManager redisManager,
                                  ChatSessionMapper sessionMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.redisManager = redisManager;
        this.sessionMapper = sessionMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 切换用户当前活跃会话
     *
     * <p>该操作不会修改 MySQL 会话状态，仅更新 Redis 中的用户活跃会话指针</p>
     *
     * @param sessionId 目标会话 ID
     * @param userId 用户 ID
     * @return 目标会话实体
     * @throws BusinessException 会话不存在或无权访问
     */
    public ChatSession activateSessionInRedis(String sessionId, String userId) {
        LambdaQueryWrapper<ChatSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId);
        ChatSession newSession = sessionMapper.selectOne(queryWrapper);
        
        if (newSession == null) {
            throw new BusinessException(
                ErrorCode.SESSION_NOT_FOUND.getCode(),
                ErrorCode.SESSION_NOT_FOUND.getMessage() + sessionId
            );
        }

        // 由于查询条件已包含 userId，无需再次校验归属关系

        String oldSessionId = redisManager.getActiveSession(userId);

        redisManager.setActiveSession(userId, sessionId);

        eventPublisher.publishEvent(new SessionSwitchedEvent(oldSessionId, sessionId, userId));

        preloadSessionL1CacheAsync(sessionId);

        return newSession;
    }

    /**
     * 异步预热会话一级缓存
     *
     * @param sessionId 会话 ID
     */
    @Async
    public void preloadSessionL1CacheAsync(String sessionId) {
    }

}
