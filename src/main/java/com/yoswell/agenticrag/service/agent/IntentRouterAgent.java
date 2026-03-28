package com.yoswell.agenticrag.service.agent;

import com.yoswell.agenticrag.dto.IntentDecision;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface IntentRouterAgent {
    
    @SystemMessage({
        "You are an intent routing classifier.",
        "Your task is to analyze the user request and strictly output the classification based on the schema.",
        "Allowed intents are: 'rag_search' (knowledge based questions), 'complex_plan' (multi-step macro tasks like compare, generate full reports), 'small_talk' (generic or casual prompts)."
    })
    IntentDecision classify(@UserMessage String userMessage);
}
