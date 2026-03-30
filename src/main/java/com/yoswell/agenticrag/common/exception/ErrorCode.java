package com.yoswell.agenticrag.common.exception;

/**
 * 全局通用错误码枚举
 */
public enum ErrorCode {

    // 基础错误


    // 业务错误码 - 用户模块 (10000 起)
    USER_NOT_EXIST("L10001", "用户不存在或已被禁用"),
    USER_ALREADY_EXIST("L10002", "用户名已存在"),
    INVALID_PASSWORD("L10003", "用户名或密码错误"),
    INVALID_REGISTRATION_PARAM("L10004", "注册参数不符合规范"),
    PASSWORD_MISMATCH("L10005", "两次密码输入不一致"),
    INVALID_LOGIN_ARGS("L10007", "用户名和密码不能为空"),
    SYSTEM_ERROR("SYS10009", "系统内部异常，请稍后重试"),
    INVALID_TOKEN("T20001", "无效的令牌"),
    TOKEN_EXPIRED("T20002", "令牌已过期或被撤销"),
    SESSION_NOT_FOUND("S30001", "会话不存在或无权访问"),
    INVALID_SESSION_ID("S30002", "无效的会话ID"),
    MISSING_SESSION_ID("S30003", "缺少会话ID请求头参数");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
