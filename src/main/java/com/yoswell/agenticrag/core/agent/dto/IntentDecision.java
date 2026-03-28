package com.yoswell.agenticrag.core.agent.dto;

/**
 * Routing Decision Entity.
 * JSON Schema mapped classification result bounded by LangChain4j structured output.
 */
public record IntentDecision(
    String intent,
    double confidence
) {}
