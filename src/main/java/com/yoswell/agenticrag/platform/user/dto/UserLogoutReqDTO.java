package com.yoswell.agenticrag.platform.user.dto;

/**
 * 用户注销请求 DTO。
 */
public class UserLogoutReqDTO {

    /**
     * Refresh Token（可选）。
     * 提供时会删除对应 refresh 记录，阻止后续换发。
     */
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
