package com.yoswell.agenticrag.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.web.security.filter.TenantAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @PostConstruct
    public void configureSecurityContext() {
        // 配置 Spring Security 上下文策略为可继承以支持 Langchain4j 异步工具执行和子线程中的权限透传
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        log.info("[Security Config] 已开启 SecurityContextHolder.MODE_INHERITABLETHREADLOCAL，支持 WebMVC 全局子线程 SecurityContext 继承传递。");
    }

    private final TenantAuthenticationFilter tenantAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(TenantAuthenticationFilter tenantAuthenticationFilter, ObjectMapper objectMapper) {
        this.tenantAuthenticationFilter = tenantAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(tenantAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(unauthorizedEntryPoint())
                .accessDeniedHandler(accessDeniedHandler())
            );

        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            
            String errorMessage = (ex != null && ex.getMessage() != null && !ex.getMessage().isEmpty()) 
                    ? ex.getMessage()
                    : ErrorCode.UNAUTHORIZED_ERROR.getMessage();
                    
            ApiResponse<Void> errorResponse = ApiResponse.error(ErrorCode.UNAUTHORIZED_ERROR.getCode(), errorMessage);
            writeResponse(response, errorResponse);
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, denied) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiResponse<Void> errorResponse = ApiResponse.error(ErrorCode.ACCESS_DENIED_ERROR);
            writeResponse(response, errorResponse);
        };
    }

    private void writeResponse(HttpServletResponse response, ApiResponse<Void> responseObj) {
        try {
            response.getWriter().write(objectMapper.writeValueAsString(responseObj));
            response.getWriter().flush();
        } catch (Exception e) {
            log.error("Failed to write security error response", e);
        }
    }
}
