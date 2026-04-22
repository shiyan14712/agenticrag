package com.yoswell.agenticrag.platform.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录响应 DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserLoginRespDTO {

    /** 令牌类型，固定为 Bearer */
    private String tokenType;

    /** Access Token（用于访问受保护资源） */
    private String accessToken;

    /** Access Token 到期时间（时间戳毫秒） */
    private Long accessTokenExpiresAt;

    /** Refresh Token（用于换发新的 Access Token） */
    private String refreshToken;

    /** Refresh Token 到期时间（时间戳毫秒） */
    private Long refreshTokenExpiresAt;

    /** 用户业务唯一标识 */
    private String userId;

    /** 登录用户名 */
    private String username;

    /** 角色集合字符串（逗号分隔） */
    private String roles;
}
