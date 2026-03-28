package com.yoswell.agenticrag.service.session;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.cache.SessionRedisManager;
import com.yoswell.agenticrag.dto.SessionCreateRequest;
import com.yoswell.agenticrag.dto.SessionUpdateRequest;
import com.yoswell.agenticrag.entity.ChatMessage;
import com.yoswell.agenticrag.entity.ChatSession;
import com.yoswell.agenticrag.event.SessionCreatedEvent;
import com.yoswell.agenticrag.repository.ChatMessageRepository;
import com.yoswell.agenticrag.repository.ChatSessionRepository;

@Service
public class SessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionRedisManager redisManager;
    private final ApplicationEventPublisher eventPublisher;

    public SessionService(ChatSessionRepository sessionRepository,
                          ChatMessageRepository messageRepository,
                          SessionRedisManager redisManager,
                          ApplicationEventPublisher eventPublisher) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.redisManager = redisManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ChatSession createSession(String userId, SessionCreateRequest request) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(Long.parseLong(userId));
        if (request != null && request.getModelId() != null) {
            session.setModelId(request.getModelId());
        }

        sessionRepository.insert(session);

        redisManager.cacheSessionMeta(session);
        redisManager.setActiveSession(userId, session.getSessionId());

        eventPublisher.publishEvent(new SessionCreatedEvent(session.getSessionId(), userId));

        return session;
    }

    public Page<ChatSession> getSessions(String userId, String status, int page, int size) {
        Page<ChatSession> p = new Page<>(page, size);
        return sessionRepository.selectPage(p, new QueryWrapper<ChatSession>()
                .eq("user_id", userId)
                .eq("status", status)
                .orderByDesc("updated_at", "pinned"));
    }

    public ChatSession getSession(String sessionId, String userId) {
        ChatSession session = redisManager.getSessionMetaOrFallback(sessionId, () ->
            sessionRepository.selectOne(new QueryWrapper<ChatSession>().eq("session_id", sessionId))
        );

        if (session == null || !session.getUserId().toString().equals(userId)) {
            throw new RuntimeException("Session not found or forbidden");
        }
        return session;
    }

    public Page<ChatMessage> getSessionMessages(String sessionId, String userId, int page, int size) {
        getSession(sessionId, userId);
        Page<ChatMessage> p = new Page<>(page, size);
        return messageRepository.selectPage(p, new QueryWrapper<ChatMessage>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at"));
    }

    @Transactional
    public ChatSession updateSession(String sessionId, String userId, SessionUpdateRequest request) {
        ChatSession session = getSession(sessionId, userId);

        boolean updated = false;
        if (request.getTitle() != null) {
            session.setTitle(request.getTitle());
            updated = true;
        }
        if (request.getPinned() != null) {
            session.setPinned(request.getPinned());
            updated = true;
        }

        if (updated) {
            sessionRepository.updateById(session);
            redisManager.cacheSessionMeta(session);
        }

        return session;
    }

    @Transactional
    public void deleteSession(String sessionId, String userId, String mode) {
        ChatSession session = getSession(sessionId, userId);

        if ("permanent".equalsIgnoreCase(mode)) {
            sessionRepository.deleteById(session.getId());
            messageRepository.delete(new QueryWrapper<ChatMessage>().eq("session_id", sessionId));
        } else {
            session.setStatus("ARCHIVED");
            session.setArchivedAt(LocalDateTime.now());
            sessionRepository.updateById(session);
        }

        redisManager.clearSessionCache(sessionId);

        if (sessionId.equals(redisManager.getActiveSession(userId))) {
            List<ChatSession> activeSessions = sessionRepository.selectList(
                new QueryWrapper<ChatSession>()
                    .eq("user_id", userId)
                    .eq("status", "ACTIVE")
                    .orderByDesc("updated_at")
            );
            if (!activeSessions.isEmpty()) {
                redisManager.setActiveSession(userId, activeSessions.get(0).getSessionId());
            } else {
                redisManager.setActiveSession(userId, null);
            }
        }
    }
}
