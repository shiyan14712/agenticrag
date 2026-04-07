package com.yoswell.agenticrag.core.memory.service;

import java.util.List;

import com.yoswell.agenticrag.core.memory.dto.ChatMessageDTO;
import com.yoswell.agenticrag.core.memory.dto.SessionMemoryViewDTO;

public interface SessionMemoryService {

    /**
     * 获取指定会话分层的纯净内存数据 (L1, L2, L3)
     */
    SessionMemoryViewDTO getSessionMemoryLayers(String sessionId);

    /**
     * 获取指定会话用于 LLM 下一轮查询的完整组装上下文 (包括通过拦截器合并的所有层级以及系统全局记忆)
     */
    List<ChatMessageDTO> getAssembledContext(String sessionId);
}
