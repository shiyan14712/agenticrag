package com.yoswell.agenticrag.platform.session.cache;

import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.common.constants.SessionCacheConstants;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class SessionRedisManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionRedisManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String getSessionMetaKey(String sessionId) {
        return SessionCacheConstants.SESSION_META_PREFIX + sessionId;
    }

    private String getSessionMessagesKey(String sessionId) {
        return SessionCacheConstants.SESSION_MESSAGES_PREFIX + sessionId;
    }

    private String getUserActiveSessionKey(String userId) {
        return SessionCacheConstants.USER_ACTIVE_SESSION_PREFIX + userId;
    }

    public void cacheSessionMeta(ChatSession session) {
        String key = getSessionMetaKey(session.getSessionId());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), SessionCacheConstants.SESSION_CACHE_TTL);
        } catch (JacksonException e) {
            e.printStackTrace();
        }
    }

    public ChatSession getSessionMetaOrFallback(String sessionId, Supplier<ChatSession> fallback) {
        String key = getSessionMetaKey(sessionId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                redisTemplate.expire(key, SessionCacheConstants.SESSION_CACHE_TTL);
                return objectMapper.readValue(value, ChatSession.class);
            } catch (JacksonException e) {
                e.printStackTrace();
            }
        }

        ChatSession fallbackSession = fallback.get();
        if (fallbackSession != null) {
            cacheSessionMeta(fallbackSession);
        }
        return fallbackSession;
    }

    public void setActiveSession(String userId, String sessionId) {
        String key = getUserActiveSessionKey(userId);
        if (sessionId == null || sessionId.isBlank()) {
            redisTemplate.delete(key);
            return;
        }
        redisTemplate.opsForValue().set(key, sessionId, SessionCacheConstants.SESSION_CACHE_TTL);
    }

    public String getActiveSession(String userId) {
        return redisTemplate.opsForValue().get(getUserActiveSessionKey(userId));
    }

    public void clearSessionCache(String sessionId) {
        redisTemplate.delete(getSessionMetaKey(sessionId));
        redisTemplate.delete(getSessionMessagesKey(sessionId));
    }
}
