package com.yoswell.agenticrag.platform.session.dto.request;

import com.yoswell.agenticrag.platform.session.constants.SessionStatusConstants;

import lombok.Data;

/**
 * 删除会话请求 DTO
 */
@Data
public class DeleteSessionRequestDTO {
    /**
     * 删除模式
     * 默认 ARCHIVED(1)，可选 DELETED（2）
     * ARCHIVED：仅修改会话状态为已归档，保留数据
     * DELETED：彻底删除会话数据，不可恢复
     * 兼容 1/2 与 archive/permanent 等历史参数
     */
    private String mode = SessionStatusConstants.ARCHIVED_VALUE;

    public Integer resolveMode() {
        return SessionStatusConstants.parseDeleteMode(mode, SessionStatusConstants.ARCHIVED);
    }
}
