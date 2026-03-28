package com.yoswell.agenticrag.common.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

@Configuration
public class LlmConfig {

    @Value("${langchain4j.llm.open-ai.base-url}")
    private String llmBaseUrl;

    @Value("${langchain4j.llm.open-ai.api-key}")
    private String llmApiKey;

    @Value("${langchain4j.llm.open-ai.model-name}")
    private String llmModelName;

    @Value("${langchain4j.llm.open-ai.temperature}")
    private Double llmTemperature;

    @Value("${langchain4j.embedding.open-ai.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.embedding.open-ai.api-key}")
    private String embeddingApiKey;

    @Value("${langchain4j.embedding.open-ai.model-name}")
    private String embeddingModelName;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(llmBaseUrl)
                .apiKey(llmApiKey)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(llmBaseUrl)
                .apiKey(llmApiKey)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
