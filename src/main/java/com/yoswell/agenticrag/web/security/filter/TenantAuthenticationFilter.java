package com.yoswell.agenticrag.web.security.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.yoswell.agenticrag.common.constants.AuthTokenCacheConstants;
import com.yoswell.agenticrag.web.security.context.TenantContextHolder;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TenantAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantAuthenticationFilter.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final StringRedisTemplate stringRedisTemplate;

    public TenantAuthenticationFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * SSE 使用 Servlet 异步派发，必须在 async dispatch 阶段继续执行鉴权过滤，
     * 否则后续由 AuthorizationFilter 校验时会因上下文缺失被拒绝。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 错误派发阶段也保留过滤，保证异常场景下上下文一致性。
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            Boolean hasKey = stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token);
            if (Boolean.TRUE.equals(hasKey)) {
                log.info("[TenantAuthenticationFilter] JWT Token 命中 Redis 缓存，继续解析认证信息");
                try {
                    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));

                    Claims claims = Jwts.parser()
                            .verifyWith(key)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

                    String tokenType = claims.get(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, String.class);
                    if (!AuthTokenCacheConstants.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                        log.warn("[TenantAuthenticationFilter] Reject non-access token on Authorization header.");
                    } else {
                        log.info("[TenantAuthenticationFilter] JWT Token 解析成功，提取用户信息构建 Authentication");
                        String userId = claims.getSubject();
                        String tenantId = claims.get("tenantId", String.class);
                        String roleClaim = claims.get("role", String.class);

                        if (tenantId != null && userId != null) {
                            List<SimpleGrantedAuthority> authorities = resolveAuthorities(roleClaim);
                            String role = authorities.get(0).getAuthority();

                            TenantUser principal = new TenantUser(userId, tenantId, role);
                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    principal, null, authorities);

                            // 写入 Spring SecurityContext（当前请求主线程）
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                            // 同步写入 TTL 上下文，确保子虚拟线程/线程池任务可通过 TtlRunnable 透明读取
                            TenantContextHolder.set(principal);
                            log.info("[TenantAuthenticationFilter] Authentication 设置成功: userId={}, tenantId={}, role={}", userId, tenantId, role);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[TenantAuthenticationFilter] Invalid JWT token: {}", e.getMessage());
                }
            } else {
                log.warn("[TenantAuthenticationFilter] JWT token not found in Redis cache or expired.");
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 防止虚拟线程被线程池复用时出现上下文泄漏（TTL 内存泄漏防护）
            TenantContextHolder.clear();
        }
    }

    private List<SimpleGrantedAuthority> resolveAuthorities(String roleClaim) {
        if (!StringUtils.hasText(roleClaim)) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        List<SimpleGrantedAuthority> authorities = Arrays.stream(roleClaim.split(",")) 
                .map(String::trim)                                         
                .filter(StringUtils::hasText)                              
                .map(SimpleGrantedAuthority::new)                          
                .toList();                                                 

        if (authorities.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return authorities;
    }
}

