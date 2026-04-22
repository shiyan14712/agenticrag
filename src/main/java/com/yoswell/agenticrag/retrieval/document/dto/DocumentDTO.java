package com.yoswell.agenticrag.retrieval.document.dto;

import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;

import java.time.LocalDateTime;

/**
 * 文档信息响应 DTO。
 * 用于返回给前端展示文档列表的基础信息。
 */
public record DocumentDTO(
        String documentId,
        String fileName,
        String fileExtension,
        DocumentProcessingStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}