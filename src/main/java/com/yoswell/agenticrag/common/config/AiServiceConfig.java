package com.yoswell.agenticrag.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yoswell.agenticrag.core.agent.ai.RagStructuredAgent;
import com.yoswell.agenticrag.core.agent.ai.SimpleChatAgent;
import com.yoswell.agenticrag.core.agent.tool.RagTool;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

@Configuration
public class AiServiceConfig {

    @Bean
    public SimpleChatAgent simpleChatAgent(ChatModel chatModel) {
        return AiServices.create(SimpleChatAgent.class, chatModel);
    }

    @Bean
    public RagStructuredAgent ragStructuredAgent(ChatModel chatModel,
                                                 ChatMemoryProvider chatMemoryProvider,
                                                 RagTool ragTool) {
        return AiServices.builder(RagStructuredAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(ragTool)
                .build();
    }
}
