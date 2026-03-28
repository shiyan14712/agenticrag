package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

public record RagSearchResult(
        String observation,
        List<RetrievedChunk> retrievedChunks,
        List<CitationDto> citations
) {
}
