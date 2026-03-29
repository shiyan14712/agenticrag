package com.yoswell.agenticrag.platform.user.service;

import com.yoswell.agenticrag.common.ApiResponse;
import com.yoswell.agenticrag.platform.user.dto.request.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserLogoutReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRefreshTokenReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRegisterReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserRegisterRespDTO;

/**
 * 用户认证领域服务接口
 */
public interface UserService {

    /**
     * 注册接口响应封装
     *
     * @param reqDTO 注册请求体
     * @return 统一响应对象
     */
    ApiResponse<UserRegisterRespDTO> registerWithResponse(UserRegisterReqDTO reqDTO);

    /**
     * 登录接口响应封装
     *
     * @param reqDTO 登录请求体
     * @return 统一响应对象
     */
    ApiResponse<UserLoginRespDTO> loginWithResponse(UserLoginReqDTO reqDTO);

    /**
     * 刷新接口响应封装
     *
     * @param reqDTO 刷新请求体
     * @return 统一响应对象
     */
    ApiResponse<UserLoginRespDTO> refreshWithResponse(UserRefreshTokenReqDTO reqDTO);

    /**
     * 注销接口响应封装
     *
     * @param authorizationHeader Authorization 请求头
     * @param reqDTO 注销请求体
     * @return 统一响应对象
     */
    ApiResponse<String> logoutWithResponse(String authorizationHeader, UserLogoutReqDTO reqDTO);

    /**
     * 用户注册
     *
     * @param reqDTO 注册请求体
     * @return 注册结果
     */
    UserRegisterRespDTO register(UserRegisterReqDTO reqDTO);

    /**
     * 用户登录
     *
     * @param reqDTO 登录请求体
     * @return 登录成功后的 token 与用户信息
     */
    UserLoginRespDTO login(UserLoginReqDTO reqDTO);

    /**
     * 使用 Refresh Token 换发 Access Token，并执行 Refresh Token 轮换
     *
     * @param refreshToken Refresh Token
     * @return 新的令牌对与用户信息
     */
    UserLoginRespDTO refreshToken(String refreshToken);

    /**
     * 注销当前会话
     *
     * @param accessToken Access Token（可选）
     * @param refreshToken Refresh Token（可选）
     */
    void logout(String accessToken, String refreshToken);

    /**
     * 校验 Token 是否有效
     *
     * @param token JWT token
     * @return true 表示 token 有效
     */
    boolean validateToken(String token);
}
