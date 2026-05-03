package com.yoswell.agenticrag.platform.session.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * Session message query parameters.
 */
@Data
public class SessionMessageQueryRequestDTO {

    private static final int MAX_PAGE_SIZE = 200;

    @NotNull(message = "缺少查询参数：page 为必填项")
    @Min(value = 0, message = "分页参数错误：page 不能小于 0")
    private Integer page;

    @NotNull(message = "缺少查询参数：size 为必填项")
    @Min(value = 1, message = "分页参数错误：size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间")
    @Max(value = MAX_PAGE_SIZE, message = "分页参数错误：size 必须在 1 到 " + MAX_PAGE_SIZE + " 之间")
    private Integer size;

    /**
     * Include assistant tool-call and tool-result trace messages.
     */
    private Boolean includeTrace = false;
}
