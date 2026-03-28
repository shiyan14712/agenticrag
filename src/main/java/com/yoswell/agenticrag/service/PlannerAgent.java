package com.yoswell.agenticrag.service;

import com.yoswell.agenticrag.model.ExecutionPlan;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface PlannerAgent {

    @SystemMessage({
        "You are an expert planner agent.",
        "Break down the user's complex request into a sequence of actionable steps.",
        "You MUST return the output strictly adhering to the requested JSON schema representing an ExecutionPlan with a list of steps."
    })
    ExecutionPlan generatePlan(@V("userMessage") String userMessage);
}
