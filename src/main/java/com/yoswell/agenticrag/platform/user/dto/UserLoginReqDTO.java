package com.yoswell.agenticrag.platform.user.dto;

/**
 * 用户登录请求 DTO。
 */
public class UserLoginReqDTO {

    /** 登录账号名。 */
    private String username;

    /** 登录明文密码（仅用于入参接收）。 */
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
