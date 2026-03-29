package com.yoswell.agenticrag.common.exception;

/**
 * 全局通用错误码枚举
 */
public enum ErrorCode {
    
    // 基础通用状态码
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未授权或Token已过期"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统内部错误"),

    // 业务错误码 - 用户模块 (10000 起)
    USER_NOT_EXIST(10001, "用户不存在或已被禁用"),
    USER_ALREADY_EXIST(10002, "用户名已存在"),
    INVALID_PASSWORD(10003, "用户名或密码错误"),
    INVALID_REGISTRATION_PARAM(10004, "注册参数不符合规范"),
    PASSWORD_MISMATCH(10005, "两次密码输入不一致"),
    INVALID_TOKEN(10006, "无效的令牌"),
    TOKEN_EXPIRED(10007, "令牌已过期或被撤销");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
