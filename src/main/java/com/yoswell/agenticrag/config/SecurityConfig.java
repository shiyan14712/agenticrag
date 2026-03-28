package com.yoswell.agenticrag.config;

import com.yoswell.agenticrag.security.TenantAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final TenantAuthenticationFilter tenantAuthenticationFilter;

    public SecurityConfig(TenantAuthenticationFilter tenantAuthenticationFilter) {
        this.tenantAuthenticationFilter = tenantAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll() // 在本示例中允许所有，靠 Filter 完成身份注入和在 RAG 时的权限过滤
            )
            .addFilterBefore(tenantAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            
        return http.build();
    }
}
