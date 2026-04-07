package com.yoswell.agenticrag.core.memory.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserGlobalMemoryDTO {
    private Long id;
    private String userId;
    private String preferenceKey;
    private String preferenceValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
