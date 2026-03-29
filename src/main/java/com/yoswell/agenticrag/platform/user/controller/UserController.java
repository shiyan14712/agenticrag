package com.yoswell.agenticrag.platform.user.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.common.ApiResponse;
import com.yoswell.agenticrag.platform.user.dto.request.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserLogoutReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRefreshTokenReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRegisterReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserRegisterRespDTO;
import com.yoswell.agenticrag.platform.user.service.UserService;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 用户认证接口控制器
 * 仅负责参数接收与返回封装，不承载业务逻辑
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserController {

    /** 用户认证领域服务 */
    private final UserService userService;

    /**
     * 构造函数注入用户认证服务
     *
     * @param userService 用户认证服务
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册接口
     *
     * @param reqDTO 注册请求体
     * @return 注册结果
     */
    @PostMapping("/register")
    public Mono<ApiResponse<UserRegisterRespDTO>> register(@RequestBody UserRegisterReqDTO reqDTO) {
        return Mono.fromCallable(() -> ApiResponse.success(userService.register(reqDTO)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 用户登录接口
     * 返回规范化 JSON 结构，兼容 WebFlux 响应风格
        *
        * @param reqDTO 登录请求体
     * @return 登录成功时返回 token 和用户信息，失败时返回错误码与错误信息
     */
    @PostMapping("/login")
    public Mono<ApiResponse<UserLoginRespDTO>> login(@RequestBody UserLoginReqDTO reqDTO) {
        return Mono.fromCallable(() -> ApiResponse.success(userService.login(reqDTO)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 使用 Refresh Token 换发新的令牌对
        *
        * @param reqDTO 刷新请求
     * @return 新的 access/refresh token 及过期时间
     */
    @PostMapping("/refresh")
    public Mono<ApiResponse<UserLoginRespDTO>> refresh(@RequestBody UserRefreshTokenReqDTO reqDTO) {
        return Mono.fromCallable(() -> {
            // 参数在调用内部再校验会导致冗余，可以在这先校验或继续让 Service 层校验
            return ApiResponse.success(userService.refreshToken(reqDTO != null ? reqDTO.getRefreshToken() : null));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 注销当前登录态
     *
        * @param authorizationHeader 可选的 Authorization 头（用于撤销 access token）
        * @param reqDTO 可选请求体（用于撤销 refresh token）
     * @return 注销结果
     */
    @PostMapping("/logout")
    public Mono<ApiResponse<String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody(required = false) UserLogoutReqDTO reqDTO) {
        return Mono.fromCallable(() -> {
            String refreshToken = reqDTO == null ? null : reqDTO.getRefreshToken();
            userService.logout(authorizationHeader, refreshToken);
            return ApiResponse.success("Logout success");
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
