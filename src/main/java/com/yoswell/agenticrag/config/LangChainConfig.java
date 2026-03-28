package com.yoswell.agenticrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yoswell.agenticrag.memory.CustomChatMemoryStore;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

@Configuration
public class LangChainConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider(CustomChatMemoryStore customChatMemoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(50) // we handle limits manually in store, but this is framework max
                .chatMemoryStore(customChatMemoryStore)
                .build();
    }
}
