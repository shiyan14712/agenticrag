package com.yoswell.agenticrag.retrieval.document.dto;

import com.yoswell.agenticrag.retrieval.document.model.ChunkingStrategy;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;

import java.time.LocalDateTime;

public record DocumentDTO(
        String documentId,
        String fileName,
        String fileExtension,
        DocumentProcessingStatus status,
        ChunkingStrategy chunkingStrategy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}