package com.yoswell.agenticrag.platform.session.dto.response;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话详情响应 DTO
 *
 * <p>包含会话基本信息和消息分页数据</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailsRespDTO {

    /**
     * 会话基本信息
     */
    private ChatSession session;

    /**
     * 消息列表分页数据
     */
    private Page<ChatMessage> messages;
}
