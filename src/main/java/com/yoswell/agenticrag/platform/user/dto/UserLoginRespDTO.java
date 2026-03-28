package com.yoswell.agenticrag.platform.user.dto;

/**
 * 用户登录响应 DTO。
 */
public class UserLoginRespDTO {

    /** 令牌类型，固定为 Bearer。 */
    private String tokenType;

    /** Access Token（用于访问受保护资源）。 */
    private String accessToken;

    /** Access Token 到期时间（时间戳毫秒）。 */
    private Long accessTokenExpiresAt;

    /** Refresh Token（用于换发新的 Access Token）。 */
    private String refreshToken;

    /** Refresh Token 到期时间（时间戳毫秒）。 */
    private Long refreshTokenExpiresAt;

    /** 用户业务唯一标识。 */
    private String userId;

    /** 登录用户名。 */
    private String username;

    /** 角色集合字符串（逗号分隔）。 */
    private String roles;

    /** 无参构造函数，供序列化框架使用。 */
    public UserLoginRespDTO() {}

    /**
     * 全参构造函数。
     *
     * @param tokenType 令牌类型
     * @param accessToken Access Token
     * @param accessTokenExpiresAt Access Token 到期时间（时间戳毫秒）
     * @param refreshToken Refresh Token
     * @param refreshTokenExpiresAt Refresh Token 到期时间（时间戳毫秒）
     * @param userId 用户业务唯一标识
     * @param username 用户名
     * @param roles 用户角色集合字符串
     */
    public UserLoginRespDTO(String tokenType,
            String accessToken,
            Long accessTokenExpiresAt,
            String refreshToken,
            Long refreshTokenExpiresAt,
            String userId,
            String username,
            String roles) {
        this.tokenType = tokenType;
        this.accessToken = accessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshToken = refreshToken;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.userId = userId;
        this.username = username;
        this.roles = roles;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Long getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public void setAccessTokenExpiresAt(Long accessTokenExpiresAt) {
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Long getRefreshTokenExpiresAt() {
        return refreshTokenExpiresAt;
    }

    public void setRefreshTokenExpiresAt(Long refreshTokenExpiresAt) {
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }
}
