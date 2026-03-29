package com.yoswell.agenticrag.platform.user.dto.request;

/**
 * 用户注册请求 DTO
 */
public class UserRegisterReqDTO {

    /** 注册用户名 */
    private String username;

    /** 注册密码（明文，仅用于入参接收） */
    private String password;

    /** 确认密码 */
    private String confirmPassword;

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

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }
}
