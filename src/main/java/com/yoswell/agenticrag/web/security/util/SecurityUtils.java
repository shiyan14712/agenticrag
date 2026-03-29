package com.yoswell.agenticrag.web.security.util;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import reactor.core.publisher.Mono;

public final class SecurityUtils {

    private SecurityUtils() {
        // utility class
    }

    public static Mono<String> getCurrentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(authentication -> {
                    Object principal = authentication.getPrincipal();
                    if (principal instanceof TenantUser tenantUser) {
                        return tenantUser.getUserId();
                    }
                    return authentication.getName();
                })
                .switchIfEmpty(Mono.error(new BusinessException(ErrorCode.INVALID_TOKEN)));
    }

    public static Mono<String> getCurrentTenantId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(authentication -> {
                    Object principal = authentication.getPrincipal();
                    if (principal instanceof TenantUser tenantUser) {
                        return tenantUser.getTenantId();
                    }
                    return "default";
                })
                .switchIfEmpty(Mono.just("default"));
    }
}
