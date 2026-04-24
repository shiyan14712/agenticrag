package com.yoswell.agenticrag.core.agent.rag;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    private static final int REQUEST_BODY_PREVIEW_LIMIT = 1200;
    private static final int MAX_SEND_ATTEMPTS = 2;

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
            String requestBody = buildRequestBody(query, chunks);
            HttpResponse<String> response = sendWithRetry(requestBody, chunks.size());
            if (response.statusCode() >= 400) {
                String endpointResponse = formatEndpointResponse(response);
                log.warn("[Reranker Client] Reranker returned non-success response, falling back to fused ordering. {}", endpointResponse);
                return RerankOutcome.fallback(fusedOrdering, endpointResponse);
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

    private String buildRequestBody(String query, List<RetrievedChunkDTO> chunks) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        if (StringUtils.hasText(rerankerModelName)) {
            payload.put("model", rerankerModelName);
        }

        // DashScope Reranker API: query & documents must be nested under "input"
        ObjectNode input = payload.putObject("input");
        input.put("query", query);
        ArrayNode documents = input.putArray("documents");
        chunks.forEach(chunk -> documents.add(chunk.content()));

        // DashScope Reranker API: top_n & return_documents go under "parameters"
        ObjectNode parameters = payload.putObject("parameters");
        parameters.put("top_n", chunks.size());
        parameters.put("return_documents", false);

        return objectMapper.writeValueAsString(payload);
    }

    private HttpResponse<String> sendWithRetry(String requestBody, int candidateCount) throws IOException, InterruptedException {
        HttpConnectTimeoutException timeoutFailure = null;
        ConnectException connectFailure = null;
        IOException ioFailure = null;

        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            boolean forceCloseConnection = attempt > 1;
            HttpRequest request = buildRequest(requestBody, forceCloseConnection);
            if (attempt == 1) {
                logRequestSummary(request, requestBody, candidateCount);
            } else {
                log.warn("[Reranker Client] 检测到可重试连接异常，开始第 {} / {} 次调用重试。strategy=connection-close", attempt, MAX_SEND_ATTEMPTS);
                logRequestSummary(request, requestBody, candidateCount);
            }

            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpConnectTimeoutException exception) {
                timeoutFailure = exception;
                if (attempt == MAX_SEND_ATTEMPTS) {
                    throw exception;
                }
                log.warn("[Reranker Client] Reranker connection timeout on attempt {} / {}, will retry once", attempt, MAX_SEND_ATTEMPTS);
            } catch (ConnectException exception) {
                connectFailure = exception;
                if (attempt == MAX_SEND_ATTEMPTS) {
                    throw exception;
                }
                log.warn("[Reranker Client] Reranker connection failed on attempt {} / {}, will retry once", attempt, MAX_SEND_ATTEMPTS);
            } catch (IOException exception) {
                ioFailure = exception;
                if (attempt == MAX_SEND_ATTEMPTS || !isRetryableIOException(exception)) {
                    throw exception;
                }
                log.warn("[Reranker Client] Reranker transient io exception on attempt {} / {}, will retry once. reason={}",
                        attempt, MAX_SEND_ATTEMPTS, safeExceptionMessage(exception));
            }
        }

        if (timeoutFailure != null) {
            throw timeoutFailure;
        }
        if (connectFailure != null) {
            throw connectFailure;
        }
        if (ioFailure != null) {
            throw ioFailure;
        }
        throw new IOException("Reranker request failed without a captured exception");
    }

    private HttpRequest buildRequest(String requestBody, boolean forceCloseConnection) {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(rerankerApiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        if (StringUtils.hasText(rerankerApiKey)) {
            builder.header("Authorization", "Bearer " + rerankerApiKey);
        }
        if (forceCloseConnection) {
            builder.header("Connection", "close");
        }
        return builder.build();
    }

    private boolean isRetryableIOException(IOException exception) {
        return hasMessage(exception, "header parser received no bytes")
                || hasMessage(exception, "unexpected end of file from server")
                || hasMessage(exception, "connection reset")
                || hasMessage(exception, "broken pipe")
                || hasMessage(exception, "forcibly closed by the remote host");
    }

    private boolean hasMessage(Throwable throwable, String keyword) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(keyword)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeExceptionMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "<empty-message>";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...(truncated)";
    }

    private void logRequestSummary(HttpRequest request, String requestBody, int candidateCount) {
        log.info("[Reranker Client] 请求摘要: method={}, url={}, candidateCount={}, headers={}, bodyChars={}, bodyPreview={}",
                request.method(),
                request.uri(),
                candidateCount,
                formatRequestHeaders(request.headers().map()),
                requestBody.length(),
                summarizeRequestBody(requestBody));
    }

    private String formatRequestHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return "<empty-headers>";
        }
        return headers.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + ": "
                        + entry.getValue().stream()
                        .map(value -> maskHeaderValue(entry.getKey(), value))
                        .collect(Collectors.joining(", "))
                        + "]")
                .collect(Collectors.joining(", "));
    }

    private String maskHeaderValue(String headerName, String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return headerValue;
        }

        String normalizedHeader = headerName == null ? "" : headerName.toLowerCase(Locale.ROOT);
        boolean sensitiveHeader = normalizedHeader.contains("authorization")
                || normalizedHeader.contains("api-key")
                || normalizedHeader.contains("x-auth-token");

        if (!sensitiveHeader) {
            return headerValue;
        }

        if (headerValue.startsWith("Bearer ")) {
            return "Bearer " + maskSecret(headerValue.substring("Bearer ".length()));
        }
        return maskSecret(headerValue);
    }

    private String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }

        if (value.length() < 7) {
            return "...";
        }
        return value.substring(0, 5) + "..." + value.substring(value.length() - 2);
    }

    private String summarizeRequestBody(String requestBody) {
        if (!StringUtils.hasText(requestBody)) {
            return "<empty-body>";
        }

        String normalized = requestBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= REQUEST_BODY_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, REQUEST_BODY_PREVIEW_LIMIT) + "...(truncated)";
    }

    private List<RetrievedChunkDTO> mergeRerankerResponse(List<RetrievedChunkDTO> chunks, String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        // DashScope Reranker API: results are nested under output.results
        JsonNode results = root.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            log.warn("[Reranker Client] 响应中未找到 output.results，回退到原始顺序。body={}", body);
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
                                chunk.originalContent(),
                                chunk.chunkingStrategy(),
                                entry.getValue()
                        ));
                    }
                });

        if (reordered.isEmpty()) {
            return List.copyOf(chunks);
        }
        return List.copyOf(reordered);
    }

    private String formatEndpointResponse(HttpResponse<String> response) {
        String responseBody = response.body();
        if (!StringUtils.hasText(responseBody)) {
            responseBody = "<empty-body>";
        }
        return "http-status-" + response.statusCode()
                + ", headers=" + response.headers().map()
                + ", body=" + responseBody;
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
