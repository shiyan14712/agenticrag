package com.yoswell.agenticrag.core.agent.ai;

import com.yoswell.agenticrag.core.agent.dto.RagStructuredResponseDTO;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RagStructuredAgent {

    @SystemMessage({
        "You are an enterprise structured response assistant.",
        "You MUST respond ONLY with a valid JSON document matching the requested JSON Schema.",
        "Extract the information to answer, supply exact references as citations, and suggest follow-up questions.",
        "The person asking the question should be the user, so notice the follow-up question.",
    })
    RagStructuredResponseDTO askStructured(@MemoryId String sessionId, @UserMessage String userMessage);
}
