package com.yoswell.agenticrag.core.agent.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

/**
 * 豆包多模态 Embedding API 适配层
 */
public class DoubaoMultimodalEmbeddingModel implements EmbeddingModel {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final RestTemplate restTemplate;

    public DoubaoMultimodalEmbeddingModel(String baseUrl, String apiKey, String modelName) {
        // 保证 baseUrl 后面不带 / 
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>();
        // 拼接真正的 endpoint
        String endpoint = this.baseUrl + "/embeddings/multimodal";

        for (TextSegment segment : textSegments) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(this.apiKey);

            Map<String, Object> inputItem = new HashMap<>();
            inputItem.put("type", "text");
            inputItem.put("text", segment.text());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", this.modelName);
            requestBody.put("input", List.of(inputItem));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForObject(endpoint, request, Map.class);

            if (responseBody != null && responseBody.containsKey("data")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                @SuppressWarnings("unchecked")
                List<Double> vectorDouble = (List<Double>) data.get("embedding");

                float[] vector = new float[vectorDouble.size()];
                for (int i = 0; i < vectorDouble.size(); i++) {
                    vector[i] = vectorDouble.get(i).floatValue();
                }
                embeddings.add(Embedding.from(vector));
            } else {
                throw new RuntimeException("Failed to get embedding from Doubao API, response: " + responseBody);
            }
        }

        return Response.from(embeddings);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public DoubaoMultimodalEmbeddingModel build() {
            return new DoubaoMultimodalEmbeddingModel(baseUrl, apiKey, modelName);
        }
    }
}
