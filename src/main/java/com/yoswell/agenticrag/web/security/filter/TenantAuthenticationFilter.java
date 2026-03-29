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
 * 作为一个 Spring WebFilter 处于所有 HTTP 请求链的前端。
 * 此类的核心职责是：从请求头剥离 JWT，利用 Redis 与 JJWT 工具核实令牌效力，
 * 若验证通过，则将用户信息封装为身份认证信息（Authentication）塞到 Reactor WebFlux 特有的并发上下文（Context）中。
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
        // 1. 从 HTTP 请求头中获取 Authorization 字段
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        // 2. 检查 Header 是否存在且以 "Bearer " 规范开头
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            // 提取出实际的 JWT Token 字符串（截取第7个字符及以后的内容）
            String token = authHeader.substring(7);

            // 3. 响应式封装：操作 Redis (hasKey) 是可能阻塞的 I/O 操作，使用 Mono.fromCallable 将其转换为响应式流的源头
            return Mono.fromCallable(() -> stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token))
                    // 语法层面 [subscribeOn]：用于切换执行线程。boundedElastic 是专门为阻塞 I/O 设计的弹性线程池，避免堵塞底层的 Netty EventLoop (非阻塞) 线程
                    .subscribeOn(Schedulers.boundedElastic())
                    // 语法层面 [flatMap]：用于接收并平铺上一层异步操作的结果 (hasKey 的布尔值)，并在其回调中继续返回一个新的被订阅的 Mono 工作流
                    .flatMap(hasKey -> {
                        // 4. Redis 验证：确保 Token 在 Redis 缓存中存在（表明该令牌未被主动注销且未自然过期）
                        if (Boolean.TRUE.equals(hasKey)) {
                            try {
                                // 5. JWT 本地强校验签名：根据配置的 Secret 转码并生成签名的数学秘钥
                                SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
                                
                                // 语法层面 [链式建造者]：Jwts严格验证 JWT 的防篡改签名与载荷(Payload)有效性（底层包含对 expiration 过期时间的抛出检查）
                                Claims claims = Jwts.parser()
                                        .verifyWith(key)
                                        .build()
                                        .parseSignedClaims(token)
                                        .getPayload();

                                // 6. 验证 Token 类型：API 授权只能使用 Access Token，拒绝 Refresh Token 越权发起业务请求
                                String tokenType = claims.get(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, String.class);
                                if (!AuthTokenCacheConstants.TOKEN_TYPE_ACCESS.equals(tokenType)) {
                                    logger.warn("[TenantAuthenticationFilter] Reject non-access token on Authorization header.");
                                    return chain.filter(exchange); // 不合法则直接放行（作为无权匿名用户），交由后续安全拦截器触发 HTTP 401
                                }

                                // 7. 提取业务关键载荷：用户ID (定义在Subject中)、租户ID、角色
                                String userId = claims.getSubject();
                                String tenantId = claims.get("tenantId", String.class);
                                String roleClaim = claims.get("role", String.class);

                                if (tenantId != null && userId != null) {
                                    // 8. 权限解析：将 String 类型的角色转换为 Spring Security 能识别的 GrantedAuthority 列表
                                    List<SimpleGrantedAuthority> authorities = resolveAuthorities(roleClaim);
                                    String role = authorities.isEmpty() ? "ROLE_USER" : authorities.get(0).getAuthority();
                                    
                                    // 9. 拼装身份凭证：包装为框架支持的 Authentication 认证主体对象
                                    TenantUser principal = new TenantUser(userId, tenantId, role);
                                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                            principal, null, authorities);

                                    // 10. 核心注入响应链：继续向下执行过滤器链，并在返回时伴随写入响应式的 Context
                                    // 语法层面 [contextWrite]：在 WebFlux 等事件驱动模型中不可使用 ThreadLocal 存变量，必须要用 contextWrite 向整个响应流的作用域中广播挂载状态
                                    return chain.filter(exchange)
                                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                                }
                            } catch (Exception e) {
                                // 异常处理：若 Token 被篡改或格式错误等验证失败抛出任何 Exception，仅打日志而绝不阻断崩溃
                                logger.warn("[TenantAuthenticationFilter] Invalid JWT token: " + e.getMessage());
                            }
                        } else {
                            logger.warn("[TenantAuthenticationFilter] JWT token not found in Redis cache or expired.");
                        }
                        
                        // 兜底路径：如果 Redis 没搜到、令牌被篡改亦或是任何原因失败，都正常放行请求事件
                        // (注意：此时 Reactor Context 没写入任何身份，会被下游配置的 .authenticated() 判定访问墙阻尼拦截)
                        return chain.filter(exchange);
                    });
        }

        // 如果请求根本没带 Bearer Token，直接交接给下一个过滤器处理（视为完全空白的匿名请求流）
        return chain.filter(exchange);
    }

    /**
     * 将包含角色的字符串声明解析为 Spring Security 标准可鉴别的权限列表
     * 
     * @param roleClaim JWT 中携带的角色字符串（格式可能为："ROLE_ADMIN,ROLE_USER" 或单字符串）
     * @return 权限授权封装列表 List<SimpleGrantedAuthority>
     */
    private List<SimpleGrantedAuthority> resolveAuthorities(String roleClaim) {
        // 边界防护：如果 JWT 里面的角色为空或未携带，赋予一个系统最低的基础操作角色
        if (!StringUtils.hasText(roleClaim)) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // 语法层面 [Stream API 与方法引用]：采用 Java 8 Stream 处理将逗号分割的字符串加工为所需的复杂安全对象列表
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roleClaim.split(",")) // 步骤 1：按照逗号拆包为数组流
                .map(String::trim)                                         // 步骤 2：对每个切出来的元素调用去空格
                .filter(StringUtils::hasText)                              // 步骤 3：过滤掉由于可能存在连续逗号从而解析出来的 "空" 字符串
                .map(SimpleGrantedAuthority::new)                          // 步骤 4：通过对象构造函数的推导，等价于 new SimpleGrantedAuthority(str)
                .toList();                                                 // 步骤 5：语法层面 [Java 16+] 的终端操作，快捷聚合并生成一个不可变 List

        // 防御性编程：如果提供的角色字符串仅仅只是一堆逗号或全空格（通过前面剔除导致全抛弃），最后返回空流时补充安全底线拦截
        if (authorities.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return authorities;
    }
}
