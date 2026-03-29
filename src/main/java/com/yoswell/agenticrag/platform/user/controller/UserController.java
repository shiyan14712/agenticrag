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
 * 用户身份认证与授权控制器
 *
 * <p>提供系统的用户注册、登录、登出以及 Token 刷新端点。
 * 遵循 WebFlux 异步响应式规范并包装标准统一下发格式 ApiResponse。
 * 控制器层仅负责参数捕获和模型封装，不承载核心的业务校验逻辑。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class UserController {

    /**
     * 用户认证领域服务
     */
    private final UserService userService;

    /**
     * 构造函数注入用户认证服务
     *
     * @param userService 用户认证服务实现
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 新用户注册端点
     *
     * @param reqDTO 包含注册必要信息的请求体 (如用户名、密码等)
     * @return 包含新创建的用户信息的响应流
     */
    @PostMapping("/register")
    public Mono<ApiResponse<UserRegisterRespDTO>> register(@RequestBody UserRegisterReqDTO reqDTO) {
        return Mono.fromCallable(() -> ApiResponse.success(userService.register(reqDTO)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 用户登录端点
     *
     * <p>验证用户凭证并下发双 Token 结构 (Access Token 和 Refresh Token)。
     * 返回规范化 JSON 结构，兼容 WebFlux 响应风格。</p>
     *
     * @param reqDTO 登录请求体 (包含账户、密码等凭据)
     * @return 登录成功时返回包含 Token 结构和用户信息的响应流，失败时返回对应的错误码与错误信息
     */
    @PostMapping("/login")
    public Mono<ApiResponse<UserLoginRespDTO>> login(@RequestBody UserLoginReqDTO reqDTO) {
        return Mono.fromCallable(() -> ApiResponse.success(userService.login(reqDTO)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 令牌刷新端点 (Refresh Token)
     *
     * <p>使用在登录时获取的长效 Refresh Token 换发新的有效 Access Token 对。</p>
     *
     * @param reqDTO 刷新请求体，包含现有的 Refresh Token
     * @return 包含新的 Access/Refresh Token 以及过期时间的响应流
     */
    @PostMapping("/refresh")
    public Mono<ApiResponse<UserLoginRespDTO>> refresh(@RequestBody UserRefreshTokenReqDTO reqDTO) {
        return Mono.fromCallable(() -> {
            // 参数在调用内部再校验会导致冗余，可以在这先校验或继续让 Service 层校验
            return ApiResponse.success(userService.refreshToken(reqDTO != null ? reqDTO.getRefreshToken() : null));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 用户登出/注销端点
     *
     * <p>清理当前会话的上下文并使绑定的 Token 在缓存层面失效 (列入黑名单)。</p>
     *
     * @param authorizationHeader 请求头中附带的 Authorization 凭证 (用于撤销当前的 Access Token)
     * @param reqDTO              可选的请求体，用于撤销绑定的 Refresh Token
     * @return 包含登出成功消息确认的响应流
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
