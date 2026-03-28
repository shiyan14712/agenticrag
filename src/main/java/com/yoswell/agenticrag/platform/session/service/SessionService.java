package com.yoswell.agenticrag.platform.session.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.dto.SessionCreateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.SessionUpdateRequestDTO;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.event.SessionCreatedEvent;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

@Service
public class SessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SessionRedisManager redisManager;
    private final ApplicationEventPublisher eventPublisher;

    public SessionService(ChatSessionMapper sessionMapper,
                          ChatMessageMapper messageMapper,
                          SessionRedisManager redisManager,
                          ApplicationEventPublisher eventPublisher) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.redisManager = redisManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ChatSession createSession(String userId, SessionCreateRequestDTO request) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(Long.parseLong(userId));
        if (request != null && request.getModelId() != null) {
            session.setModelId(request.getModelId());
        }

        sessionMapper.insert(session);

        redisManager.cacheSessionMeta(session);
        redisManager.setActiveSession(userId, session.getSessionId());

        eventPublisher.publishEvent(new SessionCreatedEvent(session.getSessionId(), userId));

        return session;
    }

    public Page<ChatSession> getSessions(String userId, String status, int page, int size) {
        Page<ChatSession> p = new Page<>(page, size);
        return sessionMapper.selectPage(p, new QueryWrapper<ChatSession>()
                .eq("user_id", userId)
                .eq("status", status)
                .orderByDesc("updated_at", "pinned"));
    }

    public ChatSession getSession(String sessionId, String userId) {
        ChatSession session = redisManager.getSessionMetaOrFallback(sessionId, () ->
            sessionMapper.selectOne(new QueryWrapper<ChatSession>().eq("session_id", sessionId))
        );

        if (session == null || !session.getUserId().toString().equals(userId)) {
            throw new RuntimeException("Session not found or forbidden");
        }
        return session;
    }

    public Page<ChatMessage> getSessionMessages(String sessionId, String userId, int page, int size) {
        getSession(sessionId, userId);
        Page<ChatMessage> p = new Page<>(page, size);
        return messageMapper.selectPage(p, new QueryWrapper<ChatMessage>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at"));
    }
    public void verifySessionAccess(String sessionId, String userId) {
        if ("default_session".equals(sessionId)) {
            return;
        }
        // This will naturally throw an exception if the session doesn't belong to the user or doesn't exist
        getSession(sessionId, userId);
    }

    public java.util.Map<String, Object> getSessionDetails(String sessionId, String userId, int page, int size) {
        ChatSession session = getSession(sessionId, userId);
        Page<ChatMessage> messages = getSessionMessages(sessionId, userId, page, size);
        return java.util.Map.of(
            "session", session,
            "messages", messages
        );
    }
    @Transactional
    public ChatSession updateSession(String sessionId, String userId, SessionUpdateRequestDTO request) {
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
            sessionMapper.updateById(session);
            redisManager.cacheSessionMeta(session);
        }

        return session;
    }

    @Transactional
    public void deleteSession(String sessionId, String userId, String mode) {
        ChatSession session = getSession(sessionId, userId);

        if ("permanent".equalsIgnoreCase(mode)) {
            sessionMapper.deleteById(session.getId());
            messageMapper.delete(new QueryWrapper<ChatMessage>().eq("session_id", sessionId));
        } else {
            session.setStatus("ARCHIVED");
            session.setArchivedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        redisManager.clearSessionCache(sessionId);

        if (sessionId.equals(redisManager.getActiveSession(userId))) {
            List<ChatSession> activeSessions = sessionMapper.selectList(
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
