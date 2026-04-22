package com.yoswell.agenticrag.core.agent.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface EnterpriseAgent {

    @SystemMessage({
            "You are an enterprise AI assistant.",
            "Use tools sequentially if needed. Maintain a professional tone.",
            "Reason step by step before calling a tool.",
            "If you retrieve failed, then you WILL NOT answer arbitrarily but must indicate that no content was found."
    })
    TokenStream chat(@MemoryId String sessionId, @UserMessage String userMessage);

}
