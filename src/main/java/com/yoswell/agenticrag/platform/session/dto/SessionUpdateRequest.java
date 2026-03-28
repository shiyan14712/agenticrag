package com.yoswell.agenticrag.platform.session.dto;

import lombok.Data;

@Data
public class SessionUpdateRequest {
    private String title;
    private Boolean pinned;
}
