package com.yoswell.agenticrag.config;

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

    /**
     * 同步聊天语言模型，用于普通的同步链式调用或子 Agent
     */
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

    /**
     * 流式聊天语言模型，用于 WebFlux SSE 场景中流式响应给前端
     */
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

    /**
     * Embedding 向量化模型，用于将文本转换为向量
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
    
    // Reranker 占位配置，由于 LangChain4j 的重排序模型（如 CohereScoringModel）通常在额外的模块中
    // 实际中您可引入 `langchain4j-cohere` 或自行实现 ScoringModel 接口对接特定算法。
    /*
    @Value("${langchain4j.reranker.api-key}")
    private String rerankerApiKey;
    
    @Bean
    public ScoringModel scoringModel() {
        return CohereScoringModel.builder()
                .apiKey(rerankerApiKey)
                .modelName("rerank-multilingual-v2.0")
                .build();
    }
    */
}
