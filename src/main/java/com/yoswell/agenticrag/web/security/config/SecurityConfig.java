package com.yoswell.agenticrag.web.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;

import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.web.security.filter.TenantAuthenticationFilter;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 响应式安全核心配置类。
 * 基于 WebFlux 框架配置基于租户的 JWT 身份认证、端点鉴权规则，
 * 同时统一了由于 Filter 产生安全异常时的全局 JSON 格式接口响应规范。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final TenantAuthenticationFilter tenantAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TenantAuthenticationFilter tenantAuthenticationFilter, ObjectMapper objectMapper) {
        this.tenantAuthenticationFilter = tenantAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * 密码编码器配置（系统全局采用 BCrypt 强哈希算法存储）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 响应式安全拦截链装配。
     * 1. 禁用 CSRF，完全采用无状态的 JWT token 通信。
     * 2. 定义路由与全站访问规则（免登录 vs 需鉴权）。
     * 3. 在标准 Authentication 前注入多租户扩展自定义 Filter。
     * 4. 显式接管并抛接 WebFilter 侧的安全异常 (401与403)，防止向上层暴露空白状态码。
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(auth -> auth
                // 开发公共或授权专属端点免鉴权
                .pathMatchers("/api/v1/auth/**").permitAll()
                // 所有其他未显式配置的业务端点，默认强制鉴权
                .anyExchange().authenticated()
            )
            // 注入自定义基于 Redis/JJWT 的多租户认证过滤链
            .addFilterAt(tenantAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            // 配置 Security 专属的异常响应映射拦截器，使其符合全站契约规范
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(unauthorizedEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            );

        return http.build();
    }

    /**
     * 自定义的 401 Unauthorized 处理器。
     * 用于兜底处理请求未附带 Token 或 Token 校验失败等未通过身份认证的情况。
     */
    private ServerAuthenticationEntryPoint unauthorizedEntryPoint() {
        return (exchange, ex) -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            
            // 提取具体的安全异常消息（如 Token过期），兜底使用全局统一消息
            String errorMessage = (ex != null && ex.getMessage() != null && !ex.getMessage().isEmpty()) 
                    ? ex.getMessage()
                    : ErrorCode.UNAUTHORIZED_ERROR.getMessage();
                    
            ApiResponse<Void> errorResponse = ApiResponse.error(ErrorCode.UNAUTHORIZED_ERROR.getCode(), errorMessage);
            return writeResponse(exchange, errorResponse);
        };
    }

    /**
     * 自定义的 403 Forbidden 处理器。
     * 用于处理 Token 合法，但所持角色或租户权限无权访问目标资源的情况。
     */
    private ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            ApiResponse<Void> errorResponse = ApiResponse.error(ErrorCode.ACCESS_DENIED_ERROR);
            return writeResponse(exchange, errorResponse);
        };
    }

    /**
     * 工具方法：将遵循标准契约的 ApiResponse 结构序列化为 JSON 二进制流，并写入到底层 Reactor 的 Response 中。
     */
    private Mono<Void> writeResponse(ServerWebExchange exchange, ApiResponse<Void> responseObj) {
        return Mono.defer(() -> {
            try {
                byte[] bytes = objectMapper.writeValueAsBytes(responseObj);
                DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                return exchange.getResponse().writeWith(Mono.just(buffer));
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }
}
