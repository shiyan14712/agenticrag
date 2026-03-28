package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

public record RetrievedChunk(
        String chunkId,
        String documentId,
        String documentName,
        String tenantId,
        String kbId,
        List<String> allowedRoles,
        int chunkIndex,
        String content,
        double score
) {
}
