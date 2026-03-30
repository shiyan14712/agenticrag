package com.yoswell.agenticrag.common.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.yoswell.agenticrag.core.agent.llm.DoubaoMultimodalEmbeddingModel;

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

    @Value("${langchain4j.llm.open-ai.max-tokens:32768}")
    private Integer llmMaxTokens;

    @Value("${langchain4j.llm.open-ai.top-p:0.8}")
    private Double llmTopP;

    @Value("${langchain4j.llm.open-ai.presence-penalty:1.5}")
    private Double llmPresencePenalty;

    @Value("${langchain4j.embedding.open-ai.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.embedding.open-ai.api-key}")
    private String embeddingApiKey;

    @Value("${langchain4j.embedding.open-ai.model-name}")
    private String embeddingModelName;

    @Value("${langchain4j.embedding.open-ai.dimensions:2048}")
    private Integer embeddingDimensions;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(llmBaseUrl)
                .apiKey(llmApiKey)
                .modelName(llmModelName)
                .temperature(llmTemperature)
                .maxTokens(llmMaxTokens)
                .topP(llmTopP)
                .presencePenalty(llmPresencePenalty)
                // "top_k": 20 和 "chat_template_kwargs": {"enable_thinking": True} 等特有参数在LangChain4j的标准OpenAiChatModel中目前无法直接注入。如果确需透传，可能需要自定义Client或拦截器实现注入 extra_body。
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
                .maxTokens(llmMaxTokens)
                .topP(llmTopP)
                .presencePenalty(llmPresencePenalty)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "langchain4j.embedding.provider", havingValue = "openai", matchIfMissing = true)
    public EmbeddingModel openAiEmbeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .dimensions(embeddingDimensions)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "langchain4j.embedding.provider", havingValue = "volcengine")
    public EmbeddingModel doubaoEmbeddingModel() {
        return DoubaoMultimodalEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .build();
    }
}
