package com.yoswell.agenticrag.platform.session.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.event.SessionSwitchedEvent;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

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

    public ChatSession activateSession(String sessionId, String userId) {
        ChatSession newSession = sessionMapper.selectOne(new QueryWrapper<ChatSession>().eq("session_id", sessionId));
        if (newSession == null) {
            throw new RuntimeException("Session not found");
        }

        if (!userId.equals(newSession.getUserId())) {
        }

        String oldSessionId = redisManager.getActiveSession(userId);

        redisManager.setActiveSession(userId, sessionId);

        eventPublisher.publishEvent(new SessionSwitchedEvent(oldSessionId, sessionId, userId));

        preloadSessionL1CacheAsync(sessionId);

        return newSession;
    }

    @Async
    public void preloadSessionL1CacheAsync(String sessionId) {
    }

}
