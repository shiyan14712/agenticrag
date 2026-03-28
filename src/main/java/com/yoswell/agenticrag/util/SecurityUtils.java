package com.yoswell.agenticrag.util;

import org.springframework.security.core.context.SecurityContextHolder;
import com.yoswell.agenticrag.web.security.model.TenantUser;

public final class SecurityUtils {

    private SecurityUtils() {
        // utility class
    }

    public static String getCurrentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new RuntimeException("Unauthorized: No authentication found");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser.getUserId();
        }
        return authentication.getName();
    }

    public static String getCurrentTenantId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "default";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser.getTenantId();
        }
        return "default";
    }
}
