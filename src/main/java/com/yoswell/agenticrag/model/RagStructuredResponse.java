package com.yoswell.agenticrag.model;

import java.util.List;

/**
 * Scene 2: Formal Non-Streaming JSON Schema explicit format
 * For users/clients pulling one-shot aggregated structured data.
 */
public record RagStructuredResponse(
    String answer,
    List<Citation> citations,
    List<String> suggestedQuestions
) {}
