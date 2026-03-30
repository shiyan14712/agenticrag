package com.yoswell.agenticrag.common.exception;

import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import com.yoswell.agenticrag.common.ApiResponse;

/**
 * 全局统一异常处理器 (WebFlux 响应式环境适用)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 1. 处理自定义业务异常 (业务逻辑主动抛出)
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /**
     * 2. 处理 JSR-303 DTO 参数校验异常 (@Valid / @Validated)
     * 在 WebFlux 环境中，对象属性校验失败通常抛出 WebExchangeBindException
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ApiResponse<Void> handleWebExchangeBindException(WebExchangeBindException e) {
        // 将所有校验失败的字段信息拼接起来，返回给前端
        String errorMsg = e.getBindingResult().getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("DTO参数校验异常: {}", errorMsg);
        return ApiResponse.error("BAD_REQUEST", errorMsg);
    }

    /**
     * 3. 处理单字段或方法级别的方法参数校验异常 (@RequestParam / @PathVariable 上的校验)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String errorMsg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("方法参数校验异常: {}", errorMsg);
        return ApiResponse.error("BAD_REQUEST", errorMsg);
    }

    /**
     * 4. 处理请求参数类型不匹配、缺参数、JSON 反序列化失败等 HTTP 层面的数据异常
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ApiResponse<Void> handleServerWebInputException(ServerWebInputException e) {
        log.warn("HTTP请求输入异常: {}", e.getReason());
        return ApiResponse.error("BAD_REQUEST", "请求参数缺失或数据格式不正确");
    }

    /**
     * 5. 处理基于 HTTP 状态码的响应框架层异常 (如 404 API 不存在, 405 Method Not Allowed 等)
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ApiResponse<Void> handleResponseStatusException(ResponseStatusException e) {
        log.warn("HTTP状态异常: {} - {}", e.getStatusCode(), e.getReason());
        return ApiResponse.error(String.valueOf(e.getStatusCode().value()), e.getReason());
    }

    /**
     * 6. 处理代码级非法参数断言 (如 Assert.notNull 抛出的异常)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数断言异常: {}", e.getMessage());
        return ApiResponse.error("BAD_REQUEST", e.getMessage());
    }

    /**
     * 7. 兜底处理所有未预料到的系统内部环境异常 (500)
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        // 这里必须用 error 级别打印堆栈，因为属于未预期的系统崩溃或 Bug
        log.error("系统内部异常: ", e);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
