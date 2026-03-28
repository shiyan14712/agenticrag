package com.yoswell.agenticrag.platform.user.service.impl;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.platform.user.dto.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.entity.SysUser;
import com.yoswell.agenticrag.platform.user.mapper.SysUserMapper;
import com.yoswell.agenticrag.platform.user.service.UserService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400}") // default 1 day in seconds
    private Long jwtExpirationSeconds;

    private static final String REDIS_TOKEN_PREFIX = "agenticrag:user:token:";

    public UserServiceImpl(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder, StringRedisTemplate stringRedisTemplate) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO reqDTO) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, reqDTO.getUsername())
                .eq(SysUser::getStatus, "ACTIVE"));

        if (user == null) {
            throw new RuntimeException("Invalid username or password");
        }

        if (!passwordEncoder.matches(reqDTO.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        // Generate JWT Token
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        
        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + jwtExpirationSeconds * 1000;
        Date now = new Date(nowMillis);
        Date exp = new Date(expMillis);

        String token = Jwts.builder()
                .subject(user.getUserId())
                .claim("tenantId", user.getUserId()) // 兼容现有拦截器的读取
                .claim("role", user.getRoles())
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();

        // 存入 Redis 用于拦截器双重校验（无状态下的会话管理），并设置同样的过期时间
        stringRedisTemplate.opsForValue().set(REDIS_TOKEN_PREFIX + token, user.getUserId(), jwtExpirationSeconds, TimeUnit.SECONDS);

        return new UserLoginRespDTO(token, user.getUserId(), user.getUsername(), user.getRoles());
    }

    @Override
    public boolean validateToken(String token) {
        // 先检查 Redis 缓存中是否存在该 Token，以此作为是否过期/被注销的凭证
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(REDIS_TOKEN_PREFIX + token));
    }
}
