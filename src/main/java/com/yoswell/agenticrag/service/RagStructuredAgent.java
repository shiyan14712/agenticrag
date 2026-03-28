package com.yoswell.agenticrag.service;

import com.yoswell.agenticrag.model.RagStructuredResponse;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface RagStructuredAgent {

    @SystemMessage({
        "You are an enterprise structured response assistant.",
        "You MUST respond ONLY with a valid JSON document matching the requested JSON Schema.",
        "Extract the information to answer, supply exact references as citations, and suggest follow-up questions."
    })
    RagStructuredResponse askStructured(@MemoryId String sessionId, @UserMessage String userMessage);
}
