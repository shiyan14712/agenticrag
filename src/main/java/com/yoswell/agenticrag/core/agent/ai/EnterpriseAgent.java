package com.yoswell.agenticrag.core.agent.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface EnterpriseAgent {

    @SystemMessage({
        "You are an enterprise AI assistant.",
        "You have access to tools for enterprise RAG and remembering user preferences.",
        "Use tools sequentially if needed. Maintain a professional tone.",      
        "Reason step by step before calling a tool."
    })
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);

}
