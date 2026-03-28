package com.yoswell.agenticrag.platform.user.service;

import com.yoswell.agenticrag.platform.user.dto.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.UserLoginRespDTO;

/**
 * 用户认证领域服务接口。
 */
public interface UserService {

    /**
     * 用户登录。
     *
     * @param reqDTO 登录请求体
     * @return 登录成功后的 token 与用户信息
     */
    UserLoginRespDTO login(UserLoginReqDTO reqDTO);

    /**
     * 校验 Token 是否有效。
     *
     * @param token JWT token
     * @return true 表示 token 有效
     */
    boolean validateToken(String token);
}
