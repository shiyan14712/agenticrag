package com.yoswell.agenticrag.common.exception;

import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.yoswell.agenticrag.common.result.ApiResponse;

/**
 * 全局统一异常处理器 (WebMVC 适用)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMsg = e.getBindingResult().getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("DTO参数校验异常: {}", errorMsg);
        return ApiResponse.error("BAD_REQUEST", errorMsg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String errorMsg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("方法参数校验异常: {}", errorMsg);
        return ApiResponse.error("BAD_REQUEST", errorMsg);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ApiResponse<Void> handleHttpInputException(Exception e) {
        log.warn("HTTP请求输入异常: {}", e.getMessage());
        return ApiResponse.error("BAD_REQUEST", "请求参数缺失或数据格式不正确");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ApiResponse<Void> handleResponseStatusException(ResponseStatusException e) {
        log.warn("HTTP状态异常: {} - {}", e.getStatusCode(), e.getReason());
        return ApiResponse.error(String.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数断言异常: {}", e.getMessage());
        return ApiResponse.error("BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统内部异常: ", e);
        return ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
