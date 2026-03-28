package com.yoswell.agenticrag.web.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.yoswell.agenticrag.web.security.filter.TenantAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final TenantAuthenticationFilter tenantAuthenticationFilter;

    public SecurityConfig(TenantAuthenticationFilter tenantAuthenticationFilter) {
        this.tenantAuthenticationFilter = tenantAuthenticationFilter;
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
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/api/v1/documents/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(tenantAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
