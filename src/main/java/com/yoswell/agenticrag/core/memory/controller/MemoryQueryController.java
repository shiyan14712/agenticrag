package com.yoswell.agenticrag.core.memory.controller;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.core.memory.dto.ChatMessageDTO;
import com.yoswell.agenticrag.core.memory.dto.SessionMemoryViewDTO;
import com.yoswell.agenticrag.core.memory.dto.UserGlobalMemoryDTO;
import com.yoswell.agenticrag.core.memory.service.SessionMemoryService;
import com.yoswell.agenticrag.core.memory.service.UserGlobalMemoryService;

/**
 * 三级上下文缓存和持久化记忆检视接口层
 */
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryQueryController {

    private final UserGlobalMemoryService userGlobalMemoryService;
    private final SessionMemoryService sessionMemoryService;

    /**
     * 获取用户全局记忆列表
     * @param userId 用户 ID
     * @return 全局记忆列表
     */
    @GetMapping("/global/{userId}")
    public ApiResponse<List<UserGlobalMemoryDTO>> getUserGlobalMemories(
            @PathVariable("userId") String userId) {
        return ApiResponse.success(userGlobalMemoryService.listByUserId(userId));
    }

    /**
     * 获取指定会话分层的纯净内存数据 (L1, L2, L3)
     * @param sessionId 会话 ID
     * @return 分层会话数据
     */
    @GetMapping("/session/{sessionId}/layers")
    public ApiResponse<SessionMemoryViewDTO> getSessionMemoryLayers(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(sessionMemoryService.getSessionMemoryLayers(sessionId));
    }
    /**
     * 获取指定会话用于 LLM 下一轮查询的完整组装上下文 (包括通过拦截器合并的所有层级以及系统全局记忆)
     * @param sessionId 会话 ID
     * @return 完整组装上下文
     */
    @GetMapping("/session/{sessionId}/context")
    public ApiResponse<List<ChatMessageDTO>> getAssembledMemoryContext(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(sessionMemoryService.getAssembledContext(sessionId));
    }
}
