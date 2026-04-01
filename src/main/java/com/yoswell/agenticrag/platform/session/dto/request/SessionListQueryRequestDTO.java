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

    /** 会话状态（兼容 0/1/2 与 ACTIVE/ARCHIVED/DELETED）。 */
    private String status = SessionStatusConstants.ACTIVE_VALUE;

    public int resolvePage() {
        if (page == null || page < 0) {
            return 0;
        }
        return page;
    }

    public int resolveSize() {
        if (size == null || size <= 0) {
            return 20;
        }
        return size;
    }

    public Integer resolveStatus() {
        return SessionStatusConstants.parseStatus(status, SessionStatusConstants.ACTIVE);
    }
}
