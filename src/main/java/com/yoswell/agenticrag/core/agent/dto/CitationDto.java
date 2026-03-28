package com.yoswell.agenticrag.core.agent.dto;

/**
 * Enterprise RAG UI Widget Component Model.
 * Represents a single traceable citation snippet sent as a structured JSON object to the frontend.
 */
public record CitationDto(
    String docId,
    String docName,
    String chunkId,
    double confidenceScore
) {}
