package com.yoswell.agenticrag.core.agent.ai;

import com.yoswell.agenticrag.core.agent.dto.IntentDecisionDTO;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface IntentRouterAgent {
    
    @SystemMessage({
        "You are an intent routing classifier.",
        "Your task is to analyze the user request and strictly output the classification based on the schema.",
        "Allowed intents are: 'rag_search' (knowledge based questions) or 'small_talk' (generic or casual prompts)."
    })
    IntentDecisionDTO classify(@UserMessage String userMessage);
}
