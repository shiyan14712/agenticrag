package com.yoswell.agenticrag.platform.user.dto.request;

/**
 * 刷新 Access Token 请求 DTO
 */
public class UserRefreshTokenReqDTO {

    /** Refresh Token（明文，来自客户端） */
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
