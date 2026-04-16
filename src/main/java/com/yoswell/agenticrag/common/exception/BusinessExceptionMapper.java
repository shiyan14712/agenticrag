package com.yoswell.agenticrag.common.exception;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * 统一异常映射器：把底层异常稳定映射为 BusinessException。
 */
public final class BusinessExceptionMapper {

    private BusinessExceptionMapper() {
    }

    /**
     * 将任意异常映射为 BusinessException，优先保留已有 BusinessException。
     *
     * @param throwable 原始异常
     * @param fallbackErrorCode 兜底错误码（为空时使用 SYSTEM_ERROR）
     * @return 统一业务异常
     */
    public static BusinessException map(Throwable throwable, ErrorCode fallbackErrorCode) {
        ErrorCode safeFallback = fallbackErrorCode == null ? ErrorCode.SYSTEM_ERROR : fallbackErrorCode;
        if (throwable == null) {
            return new BusinessException(safeFallback);
        }

        BusinessException existingBusinessException = extractBusinessException(throwable);
        if (existingBusinessException != null) {
            return existingBusinessException;
        }

        if (containsTimeout(throwable)) {
            return new BusinessException(ErrorCode.LLM_TIMEOUT);
        }

        if (containsConnectivityFailure(throwable)) {
            return new BusinessException(ErrorCode.LLM_SERVICE_UNAVAILABLE);
        }

        return new BusinessException(safeFallback);
    }

    private static BusinessException extractBusinessException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    private static boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static boolean containsConnectivityFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketException) {
                return true;
            }
            if (current instanceof EOFException) {
                return true;
            }
            if (current instanceof IOException ioException && isConnectionClosedByPeer(ioException.getMessage())) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static boolean isConnectionClosedByPeer(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("header parser received no bytes")
                || normalized.contains("unexpected end of file")
                || normalized.contains("connection reset")
                || normalized.contains("broken pipe");
    }
}
