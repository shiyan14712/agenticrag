package com.yoswell.agenticrag.platform.session.dto.request;

import com.yoswell.agenticrag.platform.session.constants.SessionStatusConstants;

import lombok.Data;

/**
 * 会话列表查询参数。
 */
@Data
public class SessionListQueryRequestDTO {

    /** 页码（从 0 开始）。 */
    private Integer page = 0;

    /** 每页大小。 */
    private Integer size = 20;

    /**
     * 会话状态（支持多种格式）
     * <p>数字格式：0/1/2</p>
     * <p>字符串格式：ACTIVE/ARCHIVED/DELETED（不区分大小写）</p>
     */
    private SessionStatusConstants status;
}
