package com.yoswell.agenticrag.platform.session.cache;

import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.common.constants.SessionCacheConstants;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话缓存管理器
 *
 * <p>封装会话元数据和用户活跃会话在 Redis 中的读写逻辑，并提供缓存回源能力</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SessionRedisManager {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private String getSessionMetaKey(String sessionId) {
        return SessionCacheConstants.SESSION_META_PREFIX + sessionId;
    }

    private String getSessionMessagesKey(String sessionId) {
        // TODO(session-messages-cache): 当前仅定义了 key，尚未实现消息分页缓存的读写接口。
        // 下一步：补充 getSessionMessagesOrFallback / cacheSessionMessages / invalidateSessionMessagesCache。
        return SessionCacheConstants.SESSION_MESSAGES_PREFIX + sessionId;
    }

    private String getUserActiveSessionKey(String userId) {
        return SessionCacheConstants.USER_ACTIVE_SESSION_PREFIX + userId;
    }

    /**
     * 写入会话元数据缓存
     *
     * @param session 会话实体
     */
    public void cacheSessionMeta(ChatSessionDO session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SESSION_ID.getCode(), ErrorCode.INVALID_SESSION_ID.getMessage());
        }

        String key = getSessionMetaKey(session.getSessionId());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), SessionCacheConstants.SESSION_CACHE_TTL);
        } catch (JacksonException e) {
            log.error("[SessionRedisManager] 会话元数据缓存写入失败: sessionId={}", session.getSessionId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "会话缓存写入失败，请稍后重试");
        }
    }

    /**
     * 获取会话元数据，缓存未命中时回源
     *
     * @param sessionId 会话 ID
     * @param fallback 缓存未命中时的回源函数
     * @return 会话实体，若不存在则返回 null
     */
    public ChatSessionDO getSessionMetaOrFallback(String sessionId, Supplier<ChatSessionDO> fallback) {
        String key = getSessionMetaKey(sessionId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                redisTemplate.expire(key, SessionCacheConstants.SESSION_CACHE_TTL);
                return objectMapper.readValue(value, ChatSessionDO.class);
            } catch (JacksonException e) {
                // 缓存内容损坏时删除坏数据，转为回源读取
                log.warn("[SessionRedisManager] 会话元数据反序列化失败，改为回源: sessionId={}", sessionId, e);
                redisTemplate.delete(key);
            }
        }

        ChatSessionDO fallbackSession = fallback.get();
        if (fallbackSession != null) {
            cacheSessionMeta(fallbackSession);
        }
        return fallbackSession;
    }

    /**
     * 设置用户当前活跃会话
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID，传空会清除活跃会话
     */
    public void setActiveSession(String userId, String sessionId) {
        String key = getUserActiveSessionKey(userId);
        if (sessionId == null || sessionId.isBlank()) {
            redisTemplate.delete(key);
            return;
        }
        redisTemplate.opsForValue().set(key, sessionId, SessionCacheConstants.SESSION_CACHE_TTL);
    }

    /**
     * 获取用户当前活跃会话 ID
     *
     * @param userId 用户 ID
     * @return 活跃会话 ID，不存在则返回 null
     */
    public String getActiveSession(String userId) {
        return redisTemplate.opsForValue().get(getUserActiveSessionKey(userId));
    }

    /**
     * 清理会话相关缓存
     *
     * @param sessionId 会话 ID
     */
    public void clearSessionCache(String sessionId) {
        // TODO(session-messages-cache): 待消息缓存落地后，按分页粒度失效，而不是仅按单 key 粗粒度清理。
        redisTemplate.delete(getSessionMetaKey(sessionId));
        redisTemplate.delete(getSessionMessagesKey(sessionId));
    }
}
