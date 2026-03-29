package com.yoswell.agenticrag.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.yoswell.agenticrag.common.ApiResponse;

/**
 * 全局统一异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验等非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return ApiResponse.error("BAD_REQUEST", "参数错误, 请检查参数");
    }

    /**
     * 处理JWT解析及验证相关异常
     */
    @ExceptionHandler(io.jsonwebtoken.JwtException.class)
    public ApiResponse<Void> handleJwtException(io.jsonwebtoken.JwtException e) {
        log.warn("JWT令牌异常: {}", e.getMessage());
        return ApiResponse.error("UNAUTHORIZED", "令牌错误, 请重新登录");
    }

    /**
     * 兜底处理所有未预料到的异常
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统内部异常: ", e);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
