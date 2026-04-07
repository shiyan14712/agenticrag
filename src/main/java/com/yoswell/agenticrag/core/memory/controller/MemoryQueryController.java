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

/**
 * Query endpoints for inspecting memory layers and assembled context.
 */
@RestController
@RequestMapping("/api/v1/memory")
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
        return ApiResponse.success(userGlobalMemoryService.listByUserId(userId));
    }

    @GetMapping("/session/{sessionId}/layers")
    public ApiResponse<SessionMemoryViewDTO> getSessionMemoryLayers(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(sessionMemoryService.getSessionMemoryLayers(sessionId));
    }

    @GetMapping("/session/{sessionId}/context")
    public ApiResponse<List<ChatMessageDTO>> getAssembledMemoryContext(
            @PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(sessionMemoryService.getAssembledContext(sessionId));
    }
}
