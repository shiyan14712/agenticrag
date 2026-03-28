package com.yoswell.agenticrag.model;

/**
 * Scene 4A: Routing Decision Entity
 * JSON Schema mapped classification result bounded by LangChain4j structured output.
 */
public record IntentDecision(
    String intent, 
    double confidence
) {}
