package com.yoswell.agenticrag.platform.session.dto.request;

import lombok.Data;

/**
 * 会话消息查询参数。
 */
@Data
public class SessionMessageQueryRequestDTO {

    /** 页码（从 0 开始）。 */
    private Integer page = 0;

    /** 每页大小。 */
    private Integer size = 50;
}
