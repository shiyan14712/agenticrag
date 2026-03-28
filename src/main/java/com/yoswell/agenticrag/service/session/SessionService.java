package com.yoswell.agenticrag.service.session;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        // UUID v7 placeholder, using v4 here for simplicity
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        if (request != null && request.getModelId() != null) {
            session.setModelId(request.getModelId());
        }
        
        session = sessionRepository.save(session);
        
        redisManager.cacheSessionMeta(session);
        // Do not switch immediately here, or maybe switch
        redisManager.setActiveSession(userId, session.getSessionId());

        eventPublisher.publishEvent(new SessionCreatedEvent(session.getSessionId(), userId));

        return session;
    }

    public Page<ChatSession> getSessions(String userId, String status, int page, int size) {
        return sessionRepository.findByUserIdAndStatus(userId, status, 
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "pinned")));
    }

    public ChatSession getSession(String sessionId, String userId) {
        ChatSession session = redisManager.getSessionMetaOrFallback(sessionId, () -> 
            sessionRepository.findBySessionId(sessionId).orElse(null)
        );

        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("Session not found or forbidden");
        }
        return session;
    }

    public Page<ChatMessage> getSessionMessages(String sessionId, String userId, int page, int size) {
        // Validate access
        getSession(sessionId, userId);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId, PageRequest.of(page, size));
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
            session = sessionRepository.save(session);
            redisManager.cacheSessionMeta(session);
        }

        return session;
    }

    @Transactional
    public void deleteSession(String sessionId, String userId, String mode) {
        ChatSession session = getSession(sessionId, userId);
        
        if ("permanent".equalsIgnoreCase(mode)) {
            sessionRepository.delete(session);
            // Also delete messages in a real production sys, maybe mapped by Cascade
            List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            messageRepository.deleteAll(messages);
        } else {
            session.setStatus("ARCHIVED");
            session.setArchivedAt(LocalDateTime.now());
            sessionRepository.save(session);
        }

        redisManager.clearSessionCache(sessionId);

        // switch if it was active
        if (sessionId.equals(redisManager.getActiveSession(userId))) {
            List<ChatSession> activeSessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, "ACTIVE");
            if (!activeSessions.isEmpty()) {
                redisManager.setActiveSession(userId, activeSessions.get(0).getSessionId());
            } else {
                redisManager.setActiveSession(userId, null); // Clear
            }
        }
    }
}
