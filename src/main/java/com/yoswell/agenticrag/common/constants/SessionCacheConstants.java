package com.yoswell.agenticrag.common.constants;

import java.time.Duration;

/**
 * 会话缓存相关常量。
 */
public final class SessionCacheConstants {

    private SessionCacheConstants() {
        // utility class
    }

    /** 会话元信息缓存键前缀。 */
    public static final String SESSION_META_PREFIX = "session:meta:";

    /** 会话消息缓存键前缀。 */
    public static final String SESSION_MESSAGES_PREFIX = "session:messages:";

    /** 用户当前激活会话缓存键前缀。 */
    public static final String USER_ACTIVE_SESSION_PREFIX = "user:active_session:";

    /** 会话缓存默认 TTL。 */
    public static final Duration SESSION_CACHE_TTL = Duration.ofHours(24);
}