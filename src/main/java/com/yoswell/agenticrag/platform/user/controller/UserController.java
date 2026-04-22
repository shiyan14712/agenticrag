package com.yoswell.agenticrag.platform.user.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.platform.user.dto.request.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserLogoutReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRefreshTokenReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRegisterReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserRegisterRespDTO;
import com.yoswell.agenticrag.platform.user.service.UserService;

/**
 * 用户认证控制器
 *
 * <p>提供注册、登录、令牌刷新与登出能力</p>
 * <p>本控制器仅做请求接入与响应封装，核心认证逻辑在 UserService 中实现</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserController {

    /** 用户认证服务 */
    private final UserService userService;

    /**
     * 构造函数注入认证服务
     *
     * @param userService 用户认证服务
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册
     *
     * @param reqDTO 注册请求体
     * @return 包含注册结果的统一响应
     */
    @PostMapping("/register")
    public ApiResponse<UserRegisterRespDTO> register(@RequestBody UserRegisterReqDTO reqDTO) {
        return ApiResponse.success(userService.register(reqDTO));
    }

    /**
     * 用户登录
     *
     * <p>验证用户名密码并签发 AccessToken/RefreshToken</p>
     *
     * @param reqDTO 登录请求体
     * @return 包含登录结果与令牌信息的统一响应
     */
    @PostMapping("/login")
    public ApiResponse<UserLoginRespDTO> login(@RequestBody UserLoginReqDTO reqDTO) {
        return ApiResponse.success(userService.login(reqDTO));
    }

    /**
     * 刷新令牌
     *
     * <p>使用 RefreshToken 换发新的令牌对</p>
     *
     * @param reqDTO 刷新请求体
     * @return 包含新令牌对的统一响应
     */
    @PostMapping("/refresh")
    public ApiResponse<UserLoginRespDTO> refresh(@RequestBody UserRefreshTokenReqDTO reqDTO) {
        return ApiResponse.success(userService.refreshToken(reqDTO != null ? reqDTO.getRefreshToken() : null));
    }

    /**
     * 用户登出
     *
     * <p>撤销当前 AccessToken 与可选 RefreshToken，结束当前认证状态</p>
     *
     * @param authorizationHeader Authorization 请求头
     * @param reqDTO 可选请求体（可携带 RefreshToken）
     * @return 统一响应
     */
    @PostMapping("/logout")
    public ApiResponse<String> logout(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) UserLogoutReqDTO reqDTO) {
        String refreshToken = reqDTO == null ? null : reqDTO.getRefreshToken();
        userService.logout(authorizationHeader, refreshToken);
        return ApiResponse.success("Logout success");
    }
}
