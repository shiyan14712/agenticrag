package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

/**
 * Formal Non-Streaming JSON Schema explicit format.
 * For users/clients pulling one-shot aggregated structured data.
 */
public record RagStructuredResponse(
    String answer,
    List<CitationDto> citations,
    List<String> suggestedQuestions
) {}
