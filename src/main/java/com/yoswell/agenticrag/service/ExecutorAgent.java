package com.yoswell.agenticrag.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface ExecutorAgent {

    @SystemMessage({
        "You are an executor agent responsible for resolving specific logical steps.",
        "You have access to tools that can search enterprise knowledge or perform calculations.",
        "Use the provided context containing results of previous steps to answer or resolve the current step accurately."
    })
    String executeStep(@V("stepDescription") String stepDescription, @V("previousContext") String previousContext);
}
