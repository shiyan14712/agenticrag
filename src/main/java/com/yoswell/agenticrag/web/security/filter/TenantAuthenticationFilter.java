package com.yoswell.agenticrag.web.security.filter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.yoswell.agenticrag.common.constants.AuthTokenCacheConstants;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 租户认证过滤器。
 *
 * 在每次请求进入控制器前执行以下认证流程：
 * 1. 从 Authorization 头解析 Bearer Token。
 * 2. 先在 Redis 中校验 Access Token 是否存在（TTL 过期即失效）。
 * 3. 再进行 JWT 验签并提取租户与用户信息。
 * 4. 将认证结果写入 SecurityContext，供后续业务层读取。
 */
@Component
public class TenantAuthenticationFilter extends OncePerRequestFilter {

    /** JWT 签名密钥（Base64 编码）。 */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Redis 访问模板，用于校验 Access Token 是否仍在有效期内。 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造函数注入 Redis 模板。
     *
     * @param stringRedisTemplate Redis 操作模板
     */
    public TenantAuthenticationFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 过滤器主流程：完成 Access Token 的缓存校验、JWT 验签与鉴权上下文注入。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 处理异常
     * @throws IOException IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            
            // 验证 Redis 中的 Token 缓存，实现无状态下的实时 Token 失效机制
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token))) {
                try {
                    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
                    Claims claims = Jwts.parser()
                            .verifyWith(key)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

                    String tokenType = claims.get(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, String.class);
                    if (!AuthTokenCacheConstants.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                        logger.warn("Reject non-access token on Authorization header.");
                        filterChain.doFilter(request, response);
                        return;
                    }

                    String userId = claims.getSubject();
                    String tenantId = claims.get("tenantId", String.class);
                    String roleClaim = claims.get("role", String.class);

                    if (tenantId != null && userId != null) {
                        List<SimpleGrantedAuthority> authorities = resolveAuthorities(roleClaim);

                        String role = authorities.isEmpty() ? "ROLE_USER" : authorities.get(0).getAuthority();

                        TenantUser principal = new TenantUser(userId, tenantId, role);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, authorities);

                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                } catch (Exception e) {
                    logger.warn("Invalid JWT token: " + e.getMessage());
                }
            } else {
                logger.warn("JWT token not found in Redis cache or expired.");
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 将角色字符串转换为 Spring Security Authority 列表。
     *
     * @param roleClaim JWT 中的 role 声明，支持逗号分隔
     * @return Authority 列表；为空时回退为 ROLE_USER
     */
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
