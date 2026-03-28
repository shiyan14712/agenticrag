package com.yoswell.agenticrag.platform.session.cache;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;

@Component
public class SessionRedisManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionRedisManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String getSessionMetaKey(String sessionId) {
        return "session:meta:" + sessionId;
    }

    private String getSessionMessagesKey(String sessionId) {
        return "session:messages:" + sessionId;
    }

    private String getUserActiveSessionKey(String userId) {
        return "user:active_session:" + userId;
    }

    public void cacheSessionMeta(ChatSession session) {
        String key = getSessionMetaKey(session.getSessionId());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), Duration.ofHours(24));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    public ChatSession getSessionMetaOrFallback(String sessionId, Supplier<ChatSession> fallback) {
        String key = getSessionMetaKey(sessionId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                redisTemplate.expire(key, Duration.ofHours(24));
                return objectMapper.readValue(value, ChatSession.class);
            } catch (JsonProcessingException e) {
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
        redisTemplate.opsForValue().set(getUserActiveSessionKey(userId), sessionId, Duration.ofHours(24));
    }

    public String getActiveSession(String userId) {
        return redisTemplate.opsForValue().get(getUserActiveSessionKey(userId));
    }

    public void clearSessionCache(String sessionId) {
        redisTemplate.delete(getSessionMetaKey(sessionId));
        redisTemplate.delete(getSessionMessagesKey(sessionId));
    }
}
