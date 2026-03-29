package com.yoswell.agenticrag.platform.user.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.constants.AuthTokenCacheConstants;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.user.dto.request.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRegisterReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserRegisterRespDTO;
import com.yoswell.agenticrag.platform.user.entity.SysUser;
import com.yoswell.agenticrag.platform.user.mapper.SysUserMapper;
import com.yoswell.agenticrag.platform.user.service.UserService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ROLE = "ROLE_USER";
    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{4,32}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,64}$");

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private Long accessTokenExpirationMillis;

    @Value("${jwt.refresh-expiration:604800000}")
    private Long refreshTokenExpirationMillis;

    public UserServiceImpl(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder, StringRedisTemplate stringRedisTemplate) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public UserRegisterRespDTO register(UserRegisterReqDTO reqDTO) {
        validateRegisterRequest(reqDTO);

        String normalizedUsername = reqDTO.getUsername().trim();
        long duplicatedCount = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, normalizedUsername));
        if (duplicatedCount > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXIST);
        }

        SysUser newUser = new SysUser();
        newUser.setUserId(generateUserId());
        newUser.setUsername(normalizedUsername);
        newUser.setPassword(passwordEncoder.encode(reqDTO.getPassword()));
        newUser.setRoles(DEFAULT_ROLE);
        newUser.setStatus(DEFAULT_STATUS);

        int inserted = sysUserMapper.insert(newUser);
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "注册失败，请稍后重试");
        }

        return new UserRegisterRespDTO(
                newUser.getUserId(),
                newUser.getUsername(),
                newUser.getStatus(),
                newUser.getRoles());
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO reqDTO) {
        if (reqDTO == null || !StringUtils.hasText(reqDTO.getUsername()) || !StringUtils.hasText(reqDTO.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户名和密码不能为空");
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, reqDTO.getUsername())
                .eq(SysUser::getStatus, "ACTIVE"));

        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        if (!passwordEncoder.matches(reqDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        return issueTokenPair(user);
    }

    @Override
    public UserLoginRespDTO refreshToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "Refresh token不能为空");
        }

        Claims claims = parseTokenClaims(refreshToken);
        String tokenType = claims.get(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, String.class);
        if (!AuthTokenCacheConstants.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        String userId = claims.getSubject();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        String refreshTokenHash = hashToken(refreshToken);
        String cachedUserId = stringRedisTemplate.opsForValue()
                .get(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);
        if (!StringUtils.hasText(cachedUserId) || !cachedUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        SysUser user = loadActiveUserByUserId(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_EXIST);
        }

        UserLoginRespDTO refreshed = issueTokenPair(user);

        // Refresh Token 轮换：新令牌签发后，立即撤销旧 refresh token
        stringRedisTemplate.delete(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);

        return refreshed;
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        if (StringUtils.hasText(accessToken)) {
            stringRedisTemplate.delete(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + accessToken.trim());
        }

        if (StringUtils.hasText(refreshToken)) {
            String refreshTokenHash = hashToken(refreshToken.trim());
            stringRedisTemplate.delete(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);
        }
    }

    @Override
    public boolean validateToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }

        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token));
    }

    private UserLoginRespDTO issueTokenPair(SysUser user) {
        long nowMillis = System.currentTimeMillis();

        String accessToken = generateToken(user, AuthTokenCacheConstants.TOKEN_TYPE_ACCESS, accessTokenExpirationMillis, nowMillis);
        String refreshToken = generateToken(user, AuthTokenCacheConstants.TOKEN_TYPE_REFRESH, refreshTokenExpirationMillis, nowMillis);

        cacheAccessToken(accessToken, user.getUserId());
        cacheRefreshToken(refreshToken, user.getUserId());

        return new UserLoginRespDTO(
                "Bearer",
                accessToken,
                nowMillis + accessTokenExpirationMillis,
                refreshToken,
                nowMillis + refreshTokenExpirationMillis,
                user.getUserId(),
                user.getUsername(),
                user.getRoles());
    }

    private String generateToken(SysUser user, String tokenType, long ttlMillis, long nowMillis) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        Date now = new Date(nowMillis);
        Date exp = new Date(nowMillis + ttlMillis);

        var builder = Jwts.builder()
                .subject(user.getUserId())
                .claim("tenantId", user.getUserId())
                .claim("role", user.getRoles())
                .claim(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key);

        if (AuthTokenCacheConstants.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            builder.id(UUID.randomUUID().toString());
        }

        return builder.compact();
    }

    private void cacheAccessToken(String accessToken, String userId) {
        stringRedisTemplate.opsForValue().set(
                AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + accessToken,
                userId,
                accessTokenExpirationMillis,
                TimeUnit.MILLISECONDS);
    }

    private void cacheRefreshToken(String refreshToken, String userId) {
        String refreshTokenHash = hashToken(refreshToken);
        stringRedisTemplate.opsForValue().set(
                AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash,
                userId,
                refreshTokenExpirationMillis,
                TimeUnit.MILLISECONDS);
    }

    private Claims parseTokenClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SysUser loadActiveUserByUserId(String userId) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserId, userId)
                .eq(SysUser::getStatus, "ACTIVE"));
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private String extractBearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }

        if (authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }

        return authorizationHeader;
    }

    private void validateRegisterRequest(UserRegisterReqDTO reqDTO) {
        if (reqDTO == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "注册请求参数不能为空");
        }

        if (!StringUtils.hasText(reqDTO.getUsername())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户名不能为空");
        }

        String username = reqDTO.getUsername().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REGISTRATION_PARAM.getCode(), "用户名格式错误: 4-32位字母数字或.-_");
        }

        if (!StringUtils.hasText(reqDTO.getPassword()) || !StringUtils.hasText(reqDTO.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "密码和确认密码不能为空");
        }

        if (!reqDTO.getPassword().equals(reqDTO.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }

        if (!PASSWORD_PATTERN.matcher(reqDTO.getPassword()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REGISTRATION_PARAM.getCode(), "密码格式错误: 8-64位，且包含大小写字母、数字和特殊字符");
        }
    }

    private String generateUserId() {
        return "u_" + UUID.randomUUID().toString().replace("-", "");
    }
}
