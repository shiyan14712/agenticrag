package com.yoswell.agenticrag.platform.session.dto.response;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yoswell.agenticrag.platform.session.entity.ChatMessageDO;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;

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
    private ChatSessionDO session;

    /**
     * 消息列表分页数据
     */
    private IPage<ChatMessageDO> messages;
}
