package com.yoswell.agenticrag.dto;

import lombok.Data;

@Data
public class SessionUpdateRequest {
    private String title;
    private Boolean pinned;
}
