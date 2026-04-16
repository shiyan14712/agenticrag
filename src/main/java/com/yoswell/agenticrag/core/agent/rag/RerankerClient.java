package com.yoswell.agenticrag.core.agent.rag;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RerankerClient {

    private static final Logger log = LoggerFactory.getLogger(RerankerClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String rerankerApiUrl;
    private final String rerankerApiKey;
    private final String rerankerModelName;

    public RerankerClient(ObjectMapper objectMapper,
                          @Value("${langchain4j.reranker.api-url:}") String rerankerApiUrl,
                          @Value("${langchain4j.reranker.api-key:}") String rerankerApiKey,
                          @Value("${langchain4j.reranker.model-name:}") String rerankerModelName) {
        this.objectMapper = objectMapper;
        this.rerankerApiUrl = rerankerApiUrl;
        this.rerankerApiKey = rerankerApiKey;
        this.rerankerModelName = rerankerModelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public RerankOutcome rerank(String query, List<RetrievedChunkDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return RerankOutcome.noFallback(chunks == null ? List.of() : List.copyOf(chunks));
        }

        List<RetrievedChunkDTO> fusedOrdering = List.copyOf(chunks);

        if (!StringUtils.hasText(rerankerApiUrl)) {
            log.debug("[Reranker Client] reranker api-url 未配置，跳过重排并保持融合排序结果。candidateCount={}", chunks.size());
            return RerankOutcome.fallback(fusedOrdering, "api-url-not-configured");
        }

        try {
            log.info("[Reranker Client] 开始调用 reranker: candidateCount={}, model={}, queryPreview={}",
                    chunks.size(),
                    StringUtils.hasText(rerankerModelName) ? rerankerModelName : "<default>",
                    summarizeQuery(query));
            HttpRequest request = buildRequest(query, chunks);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("[Reranker Client] Reranker returned non-success status {}, falling back to fused ordering", response.statusCode());
                return RerankOutcome.fallback(fusedOrdering, "http-status-" + response.statusCode());
            }
            List<RetrievedChunkDTO> reranked = mergeRerankerResponse(chunks, response.body());
            log.info("[Reranker Client] reranker 调用完成: status={}, returnedCount={}", response.statusCode(), reranked.size());
            return RerankOutcome.noFallback(reranked);
        } catch (HttpConnectTimeoutException exception) {
            log.warn("[Reranker Client] Reranker connection timeout, falling back to fused ordering", exception);
            return RerankOutcome.fallback(fusedOrdering, "connect-timeout");
        } catch (ConnectException exception) {
            log.warn("[Reranker Client] Reranker service unavailable, falling back to fused ordering", exception);
            return RerankOutcome.fallback(fusedOrdering, "connection-failed");
        } catch (IOException exception) {
            log.warn("[Reranker Client] Reranker request failed, falling back to fused ordering", exception);
            return RerankOutcome.fallback(fusedOrdering, "io-exception");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[Reranker Client] Reranker request interrupted, falling back to fused ordering", exception);
            return RerankOutcome.fallback(fusedOrdering, "interrupted");
        } catch (RuntimeException exception) {
            log.warn("[Reranker Client] Reranker request failed, falling back to fused ordering", exception);
            return RerankOutcome.fallback(fusedOrdering, "runtime-exception");
        }
    }

    private HttpRequest buildRequest(String query, List<RetrievedChunkDTO> chunks) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        if (StringUtils.hasText(rerankerModelName)) {
            payload.put("model", rerankerModelName);
        }
        payload.put("query", query);
        payload.put("top_n", chunks.size());
        ArrayNode documents = payload.putArray("documents");
        chunks.forEach(chunk -> documents.add(chunk.content()));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(rerankerApiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

        if (StringUtils.hasText(rerankerApiKey)) {
            builder.header("Authorization", "Bearer " + rerankerApiKey);
        }
        return builder.build();
    }

    private List<RetrievedChunkDTO> mergeRerankerResponse(List<RetrievedChunkDTO> chunks, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.copyOf(chunks);
        }

        Map<Integer, Double> rerankerScores = new LinkedHashMap<>();
        for (JsonNode result : results) {
            rerankerScores.put(result.path("index").asInt(), result.path("relevance_score").asDouble());
        }

        ArrayList<RetrievedChunkDTO> reordered = new ArrayList<>(chunks.size());
        rerankerScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    int index = entry.getKey();
                    if (index >= 0 && index < chunks.size()) {
                        RetrievedChunkDTO chunk = chunks.get(index);
                        reordered.add(new RetrievedChunkDTO(
                                chunk.chunkId(),
                                chunk.documentId(),
                                chunk.documentName(),
                                chunk.tenantId(),
                                chunk.kbId(),
                                chunk.allowedRoles(),
                                chunk.chunkIndex(),
                                chunk.content(),
                                entry.getValue()
                        ));
                    }
                });

        if (reordered.isEmpty()) {
            return List.copyOf(chunks);
        }
        return List.copyOf(reordered);
    }

    private String summarizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "<empty>";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80) + "...";
    }

    public record RerankOutcome(List<RetrievedChunkDTO> chunks, boolean fallbackApplied, String fallbackReason) {

        public static RerankOutcome noFallback(List<RetrievedChunkDTO> chunks) {
            return new RerankOutcome(List.copyOf(chunks), false, null);
        }

        public static RerankOutcome fallback(List<RetrievedChunkDTO> chunks, String fallbackReason) {
            return new RerankOutcome(List.copyOf(chunks), true, fallbackReason);
        }
    }
}
