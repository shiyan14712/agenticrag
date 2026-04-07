package com.yoswell.agenticrag.core.memory.controller;

import java.util.List;

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

import lombok.extern.slf4j.Slf4j;

/**
 * Query endpoints for inspecting memory layers and assembled context.
 */
@RestController
@RequestMapping("/api/v1/memory")
@Slf4j
public class MemoryQueryController {

    private final UserGlobalMemoryService userGlobalMemoryService;
    private final SessionMemoryService sessionMemoryService;

    public MemoryQueryController(UserGlobalMemoryService userGlobalMemoryService,
            SessionMemoryService sessionMemoryService) {
        this.userGlobalMemoryService = userGlobalMemoryService;
        this.sessionMemoryService = sessionMemoryService;
    }

    @GetMapping("/global/{userId}")
    public ApiResponse<List<UserGlobalMemoryDTO>> getUserGlobalMemories(
            @PathVariable("userId") String userId) {
        log.info("[MemoryQueryController] 查询用户全局记忆: userId={}", userId);
        List<UserGlobalMemoryDTO> memories = userGlobalMemoryService.listByUserId(userId);
        int memoryCount = memories == null ? 0 : memories.size();
        log.info("[MemoryQueryController] 用户全局记忆查询完成: userId={}, memoryCount={}", userId, memoryCount);
        return ApiResponse.success(memories);
    }

    @GetMapping("/session/{sessionId}/layers")
    public ApiResponse<SessionMemoryViewDTO> getSessionMemoryLayers(
            @PathVariable("sessionId") String sessionId) {
        log.info("[MemoryQueryController] 查询会话分层记忆: sessionId={}", sessionId);
        SessionMemoryViewDTO layers = sessionMemoryService.getSessionMemoryLayers(sessionId);
        int l1Count = layers == null || layers.getL1Messages() == null ? 0 : layers.getL1Messages().size();
        boolean hasL2 = layers != null && hasText(layers.getL2Summary());
        boolean hasL3 = layers != null && hasText(layers.getL3Summary());
        log.info("[MemoryQueryController] 会话分层记忆查询完成: sessionId={}, l1Count={}, hasL2={}, hasL3={}",
                sessionId, l1Count, hasL2, hasL3);
        return ApiResponse.success(layers);
    }

    @GetMapping("/session/{sessionId}/context")
    public ApiResponse<List<ChatMessageDTO>> getAssembledMemoryContext(
            @PathVariable("sessionId") String sessionId) {
        log.info("[MemoryQueryController] 查询会话组装上下文: sessionId={}", sessionId);
        List<ChatMessageDTO> context = sessionMemoryService.getAssembledContext(sessionId);
        int contextSize = context == null ? 0 : context.size();
        log.info("[MemoryQueryController] 会话组装上下文查询完成: sessionId={}, contextSize={}",
                sessionId, contextSize);
        return ApiResponse.success(context);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
