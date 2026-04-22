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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
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

/**
 * 用户认证服务实现。
 *
 * <p>提供注册、登录、令牌刷新、注销和令牌有效性校验能力，
 * 通过 Redis 管理 Access/Refresh Token 状态。</p>
 */
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

    /**
     * 注册用户。
     *
     * @param reqDTO 注册参数
     * @return 注册结果
     */
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }

        return new UserRegisterRespDTO(
                newUser.getUserId(),
                newUser.getUsername(),
                newUser.getStatus(),
                newUser.getRoles());
    }

    /**
     * 用户登录并签发令牌。
     *
     * @param reqDTO 登录参数
     * @return 登录结果（令牌对 + 用户信息）
     */
    @Override
    public UserLoginRespDTO login(UserLoginReqDTO reqDTO) {
        // 1. 基础参数校验
        if (reqDTO == null || !StringUtils.hasText(reqDTO.getUsername()) || !StringUtils.hasText(reqDTO.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_ARGS.getCode(), "用户名和密码不能为空");
        }

        // 2. 根据用户名查询正常（ACTIVE）状态的用户记录
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, reqDTO.getUsername())
                .eq(SysUser::getStatus, "ACTIVE"));

        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        // 3. 验证前端传输的密码与数据库中采用 BCrypt 算法保存的哈希值是否匹配
        if (!passwordEncoder.matches(reqDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        // 4. 验证通过，构建完整的 Token 对（Access/Refresh），并写入 Redis 缓存实现会话控制
        return issueTokenPair(user);
    }

    /**
     * 刷新令牌并执行 Refresh Token 轮换。
     *
     * @param refreshToken Refresh Token
     * @return 新的令牌对
     */
    @Override
    public UserLoginRespDTO refreshToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Refresh token不能为空");
        }

        Claims claims;
        try {
            claims = parseTokenClaims(refreshToken);
        } catch (io.jsonwebtoken.JwtException e) {
            throw new BadCredentialsException(ErrorCode.INVALID_TOKEN_ERROR.getMessage());
        }

        String tokenType = claims.get(AuthTokenCacheConstants.CLAIM_TOKEN_TYPE, String.class);
        if (!AuthTokenCacheConstants.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            throw new BadCredentialsException(ErrorCode.INVALID_TOKEN_ERROR.getMessage());
        }

        String userId = claims.getSubject();
        if (!StringUtils.hasText(userId)) {
            throw new BadCredentialsException(ErrorCode.INVALID_TOKEN_ERROR.getMessage());
        }

        String refreshTokenHash = hashToken(refreshToken);
        String cachedUserId = stringRedisTemplate.opsForValue()
                .get(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);
        if (!StringUtils.hasText(cachedUserId) || !cachedUserId.equals(userId)) {
            throw new CredentialsExpiredException(ErrorCode.TOKEN_EXPIRED_ERROR.getMessage());
        }

        SysUser user = loadActiveUserByUserId(userId);
        if (user == null) {
            throw new org.springframework.security.authentication.DisabledException(ErrorCode.USER_NOT_EXIST.getMessage());
        }

        UserLoginRespDTO refreshed = issueTokenPair(user);

        // Refresh Token 轮换：新令牌签发后，立即撤销旧 refresh token
        stringRedisTemplate.delete(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);

        return refreshed;
    }

    /**
     * 注销用户会话。
     *
     * @param accessToken Access Token
     * @param refreshToken Refresh Token
     */
    @Override
    public void logout(String accessToken, String refreshToken) {
        String extractedAccessToken = extractBearerToken(accessToken);
        if (StringUtils.hasText(extractedAccessToken)) {
            stringRedisTemplate.delete(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + extractedAccessToken.trim());
        }

        if (StringUtils.hasText(refreshToken)) {
            String refreshTokenHash = hashToken(refreshToken.trim());
            stringRedisTemplate.delete(AuthTokenCacheConstants.REFRESH_TOKEN_PREFIX + refreshTokenHash);
        }
    }

    /**
     * 校验 Access Token 是否仍然有效。
     *
     * @param token Access Token
     * @return true 表示有效
     */
    @Override
    public boolean validateToken(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }

        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + token));
    }

    /**
     * 签发新的 Access Token 与 Refresh Token。
     *
     * @param user 已认证用户
     * @return 登录响应
     */
    private UserLoginRespDTO issueTokenPair(SysUser user) {
        long nowMillis = System.currentTimeMillis();

        // 1. 利用 JJWT 生成含有用户基础声明(claims)的 Token 字符串
        String accessToken = generateToken(user, AuthTokenCacheConstants.TOKEN_TYPE_ACCESS, accessTokenExpirationMillis, nowMillis);
        String refreshToken = generateToken(user, AuthTokenCacheConstants.TOKEN_TYPE_REFRESH, refreshTokenExpirationMillis, nowMillis);

        // 2. 将签发出的新 Token 存入 Redis 以便统一管理（支持后续请求拦截器鉴权拦截、支持踢下线）
        cacheAccessToken(accessToken, user.getUserId());
        cacheRefreshToken(refreshToken, user.getUserId());

        // 3. 构建给前端的登录返回包
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

    /**
     * 缓存 Access Token。
     */
    private void cacheAccessToken(String accessToken, String userId) {
        stringRedisTemplate.opsForValue().set(
                AuthTokenCacheConstants.ACCESS_TOKEN_PREFIX + accessToken,
                userId,
                accessTokenExpirationMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 缓存 Refresh Token（以哈希值作为 key）。
     */
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "令牌哈希计算失败，请稍后重试");
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
            throw new BusinessException(ErrorCode.INVALID_LOGIN_ARGS.getCode(), "注册请求参数不能为空");
        }

        if (!StringUtils.hasText(reqDTO.getUsername())) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_ARGS.getCode(), "用户名不能为空");
        }

        String username = reqDTO.getUsername().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REGISTRATION_PARAM.getCode(), "用户名格式错误: 4-32位字母数字或.-_");
        }

        if (!StringUtils.hasText(reqDTO.getPassword()) || !StringUtils.hasText(reqDTO.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_ARGS.getCode(), "密码和确认密码不能为空");
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
