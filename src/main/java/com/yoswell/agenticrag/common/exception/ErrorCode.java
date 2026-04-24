package com.yoswell.agenticrag.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 全局通用错误码枚举
 */
@Getter
@RequiredArgsConstructor
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
    LLM_SERVICE_UNAVAILABLE("M50001", "大模型服务暂时不可用，请稍后重试"),
    LLM_TIMEOUT("M50002", "大模型响应超时，请稍后重试"),
    AGENT_STREAM_INTERRUPTED("M50003", "智能体执行失败，请稍后重试"),
    TITLE_GENERATION_FAILED("M50004", "会话标题生成失败，请稍后重试"),
    RERANKER_SERVICE_UNAVAILABLE("R60001", "重排序服务暂时不可用，请稍后重试"),
    RERANKER_TIMEOUT("R60002", "重排序服务响应超时，请稍后重试"),
    SESSION_NOT_FOUND("S30001", "会话不存在或无权访问"),
    INVALID_SESSION_ID("S30002", "无效的会话ID"),
    MISSING_SESSION_ID("S30003", "缺少会话ID请求头参数"),
    SESSION_ALREADY_IN_TARGET_STATUS("S30004", "会话已处于目标状态，请勿重复操作"),
    ARCHIVED_OR_DELETED_SESSION_CANNOT_BE_ACTIVATED("S30005", "已归档或已删除的会话不能被激活"),
    INVALID_SESSION_STATUS_TRANSITION("S30006", "会话状态向上转型（如从 ARCHIVED 转换到 ACTIVE）是不允许的"),
    MISSING_SESSION_MESSAGE_QUERY_PARAM("S30007", "缺少会话消息分页参数"),
    INVALID_SESSION_MESSAGE_QUERY_PARAM("S30008", "会话消息分页参数不合法"),

    // 上传与 multipart 处理错误
    UPLOAD_SIZE_EXCEEDED("D40001", "上传文件超过大小限制"),
    BAD_MULTIPART_REQUEST("D40002", "文件上传请求格式错误或文件内容不可解析"),
    DOCUMENT_ILLEGAL_ARGUMENT("D40003", "文档操作参数不完整"),
    DOCUMENT_NOT_FOUND("D40004", "文档不存在或无访问权限"),
    DOCUMENT_DELETE_FAILED("D40005", "文档安全删除失败，请稍后重试"),
    KNOWLEDGE_KEYWORD_SEARCH_FAILED("D40006", "知识关键词检索失败，请稍后重试"),
    KNOWLEDGE_VECTOR_SEARCH_FAILED("D40007", "知识向量检索失败，请稍后重试"),
    INVALID_CHUNKING_STRATEGY("D40008", "无效的分块策略，可选值：STANDARD、DECONTEXTUALISED、QA_ENRICHED"),

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

}
