package com.yoswell.agenticrag.platform.user.dto;

/**
 * 用户登录响应 DTO。
 */
public class UserLoginRespDTO {

    /** 认证成功后签发的 JWT。 */
    private String token;

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
     * @param token JWT 令牌
     * @param userId 用户业务唯一标识
     * @param username 用户名
     * @param roles 用户角色集合字符串
     */
    public UserLoginRespDTO(String token, String userId, String username, String roles) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.roles = roles;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
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
