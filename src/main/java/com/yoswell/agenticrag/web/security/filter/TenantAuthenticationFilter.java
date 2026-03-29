package com.yoswell.agenticrag.web.security.filter;

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
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.yoswell.agenticrag.common.constants.AuthTokenCacheConstants;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 响应式租户认证过滤器
 */
@Component
public class TenantAuthenticationFilter implements WebFilter {

    private static final Logger logger = LoggerFactory.getLogger(TenantAuthenticationFilter.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final StringRedisTemplate stringRedisTemplate;

    public TenantAuthenticationFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            return Mono.fromCallable(() -> stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(hasKey -> {
                        if (Boolean.TRUE.equals(hasKey)) {
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
                                    return chain.filter(exchange);
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

                                    return chain.filter(exchange)
                                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                                }
                            } catch (Exception e) {
                                logger.warn("Invalid JWT token: " + e.getMessage());
                            }
                        } else {
                            logger.warn("JWT token not found in Redis cache or expired.");
                        }
                        return chain.filter(exchange);
                    });
        }

        return chain.filter(exchange);
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
