package com.yoswell.agenticrag.dto;

/**
 * Scene 2: Enterprise RAG UI Widget Component Model
 * Represents a single traceable citation snippet sent as a structured JSON object to the frontend.
 */
public record Citation(
    String docId,
    String docName,
    String chunkId,
    double confidenceScore
) {}
