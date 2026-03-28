package com.yoswell.agenticrag.common.constants;

/**
 * 认证令牌缓存与声明常量。
 */
public final class AuthTokenCacheConstants {

    private AuthTokenCacheConstants() {
        // utility class
    }

    /** Access Token 在 Redis 中的键前缀。 */
    public static final String ACCESS_TOKEN_PREFIX = "agenticrag:user:token:";

    /** Refresh Token 哈希值在 Redis 中的键前缀。 */
    public static final String REFRESH_TOKEN_PREFIX = "agenticrag:user:refresh:";

    /** JWT 中标识 token 类型的 claim 名称。 */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";

    /** Access Token 类型值。 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** Refresh Token 类型值。 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";
}
