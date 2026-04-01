package com.yoswell.agenticrag.common.exception;

/**
 * 全局通用错误码枚举
 */
public enum ErrorCode {

    // 基础错误


    // =========================================================================
    // 【HTTP 200】 业务异常错误码 (Business Exceptions)
    // =========================================================================
    // 业务正常流转中的不通过情况（如密码错误、找不到记录等）。
    // 将由 Controller 层的 @RestControllerAdvice 全局异常处理器接管，
    // 外层返回 HTTP 200 OK，前端根据其内部的 code 进行业务分支处理。

    // 业务错误码 - 用户模块 (10000 起)
    USER_NOT_EXIST("L10001", "用户不存在或已被禁用"),
    USER_ALREADY_EXIST("L10002", "用户名已存在"),
    INVALID_PASSWORD("L10003", "用户名或密码错误"),
    INVALID_REGISTRATION_PARAM("L10004", "注册参数不符合规范"),
    PASSWORD_MISMATCH("L10005", "两次密码输入不一致"),
    INVALID_LOGIN_ARGS("L10007", "用户名和密码不能为空"),
    SYSTEM_ERROR("SYS10009", "系统内部异常，请稍后重试"),
    SESSION_NOT_FOUND("S30001", "会话不存在或无权访问"),
    INVALID_SESSION_ID("S30002", "无效的会话ID"),
    MISSING_SESSION_ID("S30003", "缺少会话ID请求头参数"),

    // =========================================================================
    // 【HTTP 401/403】 鉴权与安全拦截错误码 (Security & Authentication)
    // =========================================================================
    // 由未带凭证、Token失效或越权访问触发的底层安全异常。
    // 将被 SecurityConfig 中的 AuthenticationEntryPoint / AccessDeniedHandler 捕捉，
    // 外层强行返回真实的 HTTP 401 或 403 状态码（触发前端网关路由拦截），
    // 并且向外下发的 ApiResponse 中的 JSON code 会使用以下枚举。

    // 鉴权异常错误
    INVALID_TOKEN_ERROR("T20001", "无效的令牌"),
    TOKEN_EXPIRED_ERROR("T20002", "令牌已过期或被撤销"),
    UNAUTHORIZED_ERROR("T20003", "未授权或令牌已失效，请重新登录"),
    ACCESS_DENIED_ERROR("T30001", "权限不足，拒绝访问");

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
