package com.yoswell.agenticrag.platform.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册响应 DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserRegisterRespDTO {

    /** 用户业务唯一标识 */
    private String userId;

    /** 注册用户名 */
    private String username;

    /** 账号状态 */
    private String status;

    /** 角色字符串（逗号分隔） */
    private String roles;
}
