package com.yoswell.agenticrag.web.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.yoswell.agenticrag.web.security.model.TenantUser;

import java.io.IOException;
import java.util.Collections;

@Component
public class TenantAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // TODO：从请求头获取租户和用户身份，这里为简化示例，实际应解析 JWT Token
        String tenantId = request.getHeader("X-Tenant-ID");
        String userId = request.getHeader("X-User-ID");
        String role = request.getHeader("X-User-Role");

        if (tenantId != null && userId != null) {
            if (role == null) {
                role = "ROLE_USER";
            }

            TenantUser principal = new TenantUser(userId, tenantId, role);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, Collections.singletonList(new SimpleGrantedAuthority(role)));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
