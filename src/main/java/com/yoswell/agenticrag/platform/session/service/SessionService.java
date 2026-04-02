package com.yoswell.agenticrag.platform.session.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.constants.SessionStatusConstants;
import com.yoswell.agenticrag.platform.session.dto.request.SessionCreateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.request.SessionUpdateRequestDTO;
import com.yoswell.agenticrag.platform.session.dto.response.SessionDetailsRespDTO;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.event.SessionCreatedEvent;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SessionRedisManager redisManager;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ChatSession createSession(String userId, SessionCreateRequestDTO request) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setStatus(SessionStatusConstants.ACTIVE.getCode());
        if (request != null && request.getModelId() != null) {
            session.setModelId(request.getModelId());
        }

        sessionMapper.insert(session);

        redisManager.cacheSessionMeta(session);
        redisManager.setActiveSession(userId, session.getSessionId());

        eventPublisher.publishEvent(new SessionCreatedEvent(session.getSessionId(), userId));

        return session;
    }

    public Page<ChatSession> getSessions(String userId, SessionStatusConstants status, int page, int size) {
        int targetStatusCode = SessionStatusConstants.ACTIVE.getCode();
        if (status != null) {
            targetStatusCode = status.getCode();
        }
        Page<ChatSession> p = new Page<>(page, size);
        return sessionMapper.selectPage(p, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .eq(ChatSession::getStatus, targetStatusCode)
                .orderByDesc(ChatSession::getUpdatedAt, ChatSession::getPinned));
    }

    public ChatSession getSessionBySessionId(String sessionId, String userId) {
        ChatSession session = redisManager.getSessionMetaOrFallback(sessionId, () ->
            sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getSessionId, sessionId))
        );

        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND.getCode(), ErrorCode.SESSION_NOT_FOUND.getMessage() + sessionId);
        }
        return session;
    }

    public Page<ChatMessage> getSessionMessages(String sessionId, String userId, int page, int size) {
        getSessionBySessionId(sessionId, userId);
        Page<ChatMessage> p = new Page<>(page, size);
        return messageMapper.selectPage(p, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }
    public void verifySessionAccess(String sessionId, String userId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.MISSING_SESSION_ID.getCode(), ErrorCode.MISSING_SESSION_ID.getMessage());
        }
        // 这会自然地在会话不存在或者不属于该用户时抛出异常
        getSessionBySessionId(sessionId, userId);
    }

    public SessionDetailsRespDTO getSessionDetails(String sessionId, String userId, int page, int size) {
        ChatSession session = getSessionBySessionId(sessionId, userId);
        Page<ChatMessage> messages = getSessionMessages(sessionId, userId, page, size);
        return new SessionDetailsRespDTO(session, messages);
    }
    @Transactional
    public ChatSession updateSession(String sessionId, String userId, SessionUpdateRequestDTO request) {
        ChatSession session = getSessionBySessionId(sessionId, userId);

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
    public void deleteSession(String sessionId, String userId, SessionStatusConstants targetStatus) {
        ChatSession session = getSessionBySessionId(sessionId, userId);

        // 0 ACTIVE, 1 ARCHIVED, 2 DELETED
        if (targetStatus.getCode() < session.getStatus()) {
            log.warn("[Session Service] Invalid session status transition from {} to {}", session.getStatus(), targetStatus.getCode());
            throw new BusinessException(ErrorCode.INVALID_SESSION_STATUS_TRANSITION.getCode(), ErrorCode.INVALID_SESSION_STATUS_TRANSITION.getMessage());
        }

        if (targetStatus.getCode() == session.getStatus()) {
            log.warn("[Session Service] Session is already in target status: {}", targetStatus.getDescription());
            throw new BusinessException(ErrorCode.SESSION_ALREADY_IN_TARGET_STATUS.getCode(), ErrorCode.SESSION_ALREADY_IN_TARGET_STATUS.getMessage() + targetStatus.name());
        }

        if (targetStatus == SessionStatusConstants.ARCHIVED) {
            session.setArchivedAt(LocalDateTime.now());
        }

        session.setStatus(targetStatus.getCode());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        redisManager.clearSessionCache(sessionId);

        // Update user's current active session if necessary

        if (sessionId.equals(redisManager.getActiveSession(userId))) {
            List<ChatSession> activeSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getUserId, userId)
                    .eq(ChatSession::getStatus, SessionStatusConstants.ACTIVE.getCode())
                    .orderByDesc(ChatSession::getUpdatedAt)
            );
            if (!activeSessions.isEmpty()) {
                redisManager.setActiveSession(userId, activeSessions.getFirst().getSessionId());
            } else {
                redisManager.setActiveSession(userId, null);
            }
        }
    }
}
