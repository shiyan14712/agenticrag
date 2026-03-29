package com.yoswell.agenticrag.platform.user.service;

import com.yoswell.agenticrag.common.ApiResponse;
import com.yoswell.agenticrag.platform.user.dto.request.UserLoginReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserLoginRespDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserLogoutReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRefreshTokenReqDTO;
import com.yoswell.agenticrag.platform.user.dto.request.UserRegisterReqDTO;
import com.yoswell.agenticrag.platform.user.dto.response.UserRegisterRespDTO;

/**
 * 鐢ㄦ埛璁よ瘉棰嗗煙鏈嶅姟鎺ュ彛
 */
public interface UserService {









    /**
     * 鐢ㄦ埛娉ㄥ唽
     *
     * @param reqDTO 娉ㄥ唽璇锋眰浣?
     * @return 娉ㄥ唽缁撴灉
     */
    UserRegisterRespDTO register(UserRegisterReqDTO reqDTO);

    /**
     * 鐢ㄦ埛鐧诲綍
     *
     * @param reqDTO 鐧诲綍璇锋眰浣?
     * @return 鐧诲綍鎴愬姛鍚庣殑 token 涓庣敤鎴蜂俊鎭?
     */
    UserLoginRespDTO login(UserLoginReqDTO reqDTO);

    /**
     * 浣跨敤 Refresh Token 鎹㈠彂 Access Token锛屽苟鎵ц Refresh Token 杞崲
     *
     * @param refreshToken Refresh Token
     * @return 鏂扮殑浠ょ墝瀵逛笌鐢ㄦ埛淇℃伅
     */
    UserLoginRespDTO refreshToken(String refreshToken);

    /**
     * 娉ㄩ攢褰撳墠浼氳瘽
     *
     * @param accessToken Access Token锛堝彲閫夛級
     * @param refreshToken Refresh Token锛堝彲閫夛級
     */
    void logout(String accessToken, String refreshToken);

    /**
     * 鏍￠獙 Token 鏄惁鏈夋晥
     *
     * @param token JWT token
     * @return true 琛ㄧず token 鏈夋晥
     */
    boolean validateToken(String token);
}

