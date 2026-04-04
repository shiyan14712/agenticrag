package com.yoswell.agenticrag.retrieval.document.index.dto;

import java.util.List;

/**
 * Elasticsearch 检索命中中的 _source 映射 DTO。
 */
public record KnowledgeChunkSearchHitDTO(
        String chunkId,
        String documentId,
        String documentName,
        String tenantId,
        String kbId,
        List<String> allowedRoles,
        int chunkIndex,
        String content
) {
}