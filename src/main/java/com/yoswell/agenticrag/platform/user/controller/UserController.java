package com.yoswell.agenticrag.platform.user.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.common.ApiResponse;
import com.yoswell.agenticrag.platform.user.dto.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.service.UserService;

import reactor.core.publisher.Mono;

/**
 * 用户认证接口控制器。
 * 仅负责参数接收与返回封装，不承载业务逻辑。
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
     * 用户登录接口
     * 返回规范化 JSON 结构，兼容 WebFlux 响应风格。
     *
     * @param reqDTOMono 登录请求体流
     * @return 登录成功时返回 token 和用户信息，失败时返回错误码与错误信息
     */
    @PostMapping("/login")
    public Mono<ApiResponse<UserLoginRespDTO>> login(@RequestBody Mono<UserLoginReqDTO> reqDTOMono) {
        return reqDTOMono.map(reqDTO -> {
            try {
                UserLoginRespDTO respDTO = userService.login(reqDTO);
                return ApiResponse.success(respDTO);
            } catch (Exception e) {
                return ApiResponse.<UserLoginRespDTO>error(401, e.getMessage());
            }
        });
    }
}
