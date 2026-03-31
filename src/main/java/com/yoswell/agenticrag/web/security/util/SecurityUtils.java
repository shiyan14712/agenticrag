package com.yoswell.agenticrag.web.security.util;

import com.yoswell.agenticrag.common.exception.ErrorCode;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

import com.yoswell.agenticrag.web.security.model.TenantUser;

import reactor.core.publisher.Mono;

/**
 * 响应式安全上下文工具类。
 * 用于在 WebFlux 线程/流的任何环节安全地抽取出用户的身份与租户信息。
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // utility class - 防止实例化
    }

    /**
     * 响应式地从当前 SecurityContext 中提取当前登录的用户 ID。
     *
     * @return 包含用户 ID 的 Mono 流。如果上下文不存在或无认证信息，将抛出无效令牌业务异常。
     */
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
                // 够走到这里通常代表通过了鉴权 可能是处于某种内部漏洞/代码漏写导致走入这里
                .switchIfEmpty(Mono.error(new AuthenticationCredentialsNotFoundException(ErrorCode.UNAUTHORIZED_ERROR.getMessage())));
    }

    /**
     * 响应式地从当前 SecurityContext 中提取当前登录的用户所在的租户 ID。
     * 遵循 CLAUDE.md 中租户强制隔离（Tenant Isolation Scope）的安全原则。
     *
     * @return 包含租户 ID 的 Mono 流。如果处于无状态或解析失败，默认 fallback 为 "default" 兜底租户。
     */
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
