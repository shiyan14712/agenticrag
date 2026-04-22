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
     * <p>数字格式：1=ARCHIVED（归档）/ 2=DELETED（删除）</p>
     * <p>字符串格式：ARCHIVED/DELETED（不区分大小写）</p>
     * <p>ARCHIVED：仅修改会话状态为已归档，保留数据</p>
     * <p>DELETED：彻底删除会话数据，不可恢复</p>
     */
    private SessionStatusConstants status;
}
