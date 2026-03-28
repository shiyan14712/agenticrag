package com.yoswell.agenticrag.service.session;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.cache.SessionRedisManager;
import com.yoswell.agenticrag.entity.ChatSession;
import com.yoswell.agenticrag.event.SessionSwitchedEvent;
import com.yoswell.agenticrag.repository.ChatSessionRepository;

@Service
public class SessionContextSwitcher {

    private final SessionRedisManager redisManager;
    private final ChatSessionRepository sessionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SessionContextSwitcher(SessionRedisManager redisManager,
                                  ChatSessionRepository sessionRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.redisManager = redisManager;
        this.sessionRepository = sessionRepository;
        this.eventPublisher = eventPublisher;
    }

    public ChatSession activateSession(String sessionId, String userId) {
        ChatSession newSession = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        if (!newSession.getUserId().equals(userId)) {
            throw new RuntimeException("Forbidden");
        }

        String oldSessionId = redisManager.getActiveSession(userId);

        redisManager.setActiveSession(userId, sessionId);

        eventPublisher.publishEvent(new SessionSwitchedEvent(oldSessionId, sessionId, userId));

        // Background tasks are triggered by Listeners if needed.
        // We can do pre-warming of cache here async.
        preloadSessionL1CacheAsync(sessionId);

        return newSession;
    }

    @Async
    public void preloadSessionL1CacheAsync(String sessionId) {
        // Load latest messages into redis L1 cache implicitly
        // Assuming memory manager context relies on Redis -> MySQL
    }

}
