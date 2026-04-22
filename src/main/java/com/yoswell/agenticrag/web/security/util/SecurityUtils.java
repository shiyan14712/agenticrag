package com.yoswell.agenticrag.web.security.util;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.web.security.model.TenantUser;

/**
 * 安全上下文工具类。
 * 用于在 MVC 线程模型下抽取当前认证用户的身份与租户信息。
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // utility class - 防止实例化
    }

    /**
     * 从当前 SecurityContext 中提取当前登录的用户 ID。
     *
     * @return 当前用户 ID
     */
    public static String getCurrentUserId() {
        Authentication authentication = requireAuthentication();
        Object principal = authentication.getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser.getUserId();
        }

        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            throw new AuthenticationCredentialsNotFoundException(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
        }
        return name;
    }

    /**
     * 从当前 SecurityContext 中提取完整租户用户身份。
     *
     * @return 当前租户用户身份
     */
    public static TenantUser getCurrentTenantUser() {
        Authentication authentication = requireAuthentication();
        Object principal = authentication.getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser;
        }
        throw new AuthenticationCredentialsNotFoundException(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
    }

    /**
     * 从当前 SecurityContext 中提取当前登录用户所在的租户 ID。
     * 遵循 CLAUDE.md 中租户强制隔离（Tenant Isolation Scope）的安全原则。
     *
     * @return 租户 ID。
     */
    public static String getCurrentTenantId() {
        TenantUser tenantUser = getCurrentTenantUser();
        String tenantId = tenantUser.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
        }
        return tenantId;
    }

    private static Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
        }
        return authentication;
    }
}
