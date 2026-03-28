package com.yoswell.agenticrag.service.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface SynthesizerAgent {

    @SystemMessage({
        "You are a master synthesizer agent.",
        "Your goal is to compile the final comprehensive report or answer based on the aggregated execution results context.",
        "Format your answer using clear Markdown."
    })
    TokenStream synthesize(@V("userMessage") String userMessage, @V("executionResults") String executionResults);
}
