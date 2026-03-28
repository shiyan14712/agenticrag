package com.yoswell.agenticrag.retrieval.document.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class KnowledgeChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkIndexService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public KnowledgeChunkIndexService(RestClient restClient,
                                      ObjectMapper objectMapper,
                                      @Value("${rag.index.name:enterprise_chunks}") String indexName) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.indexName = indexName;
    }

    public void indexChunks(List<KnowledgeChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        ensureIndexExists(chunks.get(0).contentVector().size());
        for (KnowledgeChunkDocument chunk : chunks) {
            try {
                Request request = new Request("PUT", "/" + indexName + "/_doc/" + chunk.chunkId());
                request.setJsonEntity(objectMapper.writeValueAsString(chunk));
                restClient.performRequest(request);
            } catch (Exception exception) {
                throw new RuntimeException("Failed to index knowledge chunk " + chunk.chunkId(), exception);
            }
        }
    }

    public List<RetrievedChunk> searchByKeyword(String query, String tenantId, List<String> allowedRoles, int size) {
        try {
            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(buildKeywordSearchPayload(query, tenantId, allowedRoles, size));
            JsonNode response = executeForJson(request);
            return parseSearchHits(response);
        } catch (Exception exception) {
            throw new RuntimeException("Keyword search failed", exception);
        }
    }

    public List<RetrievedChunk> searchByVector(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size) {
        if (queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }

        ensureIndexExists(queryVector.size());
        try {
            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(buildVectorSearchPayload(queryVector, tenantId, allowedRoles, size));
            JsonNode response = executeForJson(request);
            return parseSearchHits(response);
        } catch (Exception exception) {
            throw new RuntimeException("Vector search failed", exception);
        }
    }

    private void ensureIndexExists(int vectorDimensions) {
        if (indexReady.get()) {
            return;
        }

        synchronized (indexReady) {
            if (indexReady.get()) {
                return;
            }

            if (indexExists()) {
                indexReady.set(true);
                return;
            }

            createIndex(vectorDimensions);
            indexReady.set(true);
        }
    }

    private boolean indexExists() {
        try {
            Request request = new Request("HEAD", "/" + indexName);
            restClient.performRequest(request);
            return true;
        } catch (ResponseException exception) {
            return exception.getResponse().getStatusLine().getStatusCode() != 404;
        } catch (IOException exception) {
            throw new RuntimeException("Failed to check Elasticsearch index existence", exception);
        }
    }

    private void createIndex(int vectorDimensions) {
        try {
            Request request = new Request("PUT", "/" + indexName);
            request.setJsonEntity(buildCreateIndexPayload(vectorDimensions));
            restClient.performRequest(request);
            log.info("Created Elasticsearch chunk index: {}", indexName);
        } catch (ResponseException exception) {
            int statusCode = exception.getResponse().getStatusLine().getStatusCode();
            if (statusCode != 400) {
                throw new RuntimeException("Failed to create Elasticsearch index", exception);
            }
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create Elasticsearch index", exception);
        }
    }

    private JsonNode executeForJson(Request request) throws IOException {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    private List<RetrievedChunk> parseSearchHits(JsonNode response) {
        JsonNode hits = response.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) {
            return List.of();
        }

        ArrayList<RetrievedChunk> chunks = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            ArrayList<String> roles = new ArrayList<>();
            JsonNode roleNode = source.path("allowedRoles");
            if (roleNode.isArray()) {
                roleNode.forEach(node -> roles.add(node.asText()));
            }

            chunks.add(new RetrievedChunk(
                    source.path("chunkId").asText(hit.path("_id").asText()),
                    source.path("documentId").asText(),
                    source.path("documentName").asText(),
                    source.path("tenantId").asText(),
                    source.path("kbId").asText(),
                    List.copyOf(roles),
                    source.path("chunkIndex").asInt(),
                    source.path("content").asText(),
                    hit.path("_score").asDouble(0.0d)
            ));
        }
        return List.copyOf(chunks);
    }

    private String buildKeywordSearchPayload(String query, String tenantId, List<String> allowedRoles, int size) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", size);
        root.set("_source", sourceFields());

        ObjectNode boolNode = root.putObject("query").putObject("bool");
        ArrayNode must = boolNode.putArray("must");
        must.addObject()
                .putObject("match")
                .putObject("content")
                .put("query", query);
        boolNode.set("filter", buildSecurityFilters(tenantId, allowedRoles));
        return objectMapper.writeValueAsString(root);
    }

    private String buildVectorSearchPayload(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", size);
        root.set("_source", sourceFields());

        ObjectNode knn = root.putObject("knn");
        knn.put("field", "contentVector");
        knn.set("query_vector", objectMapper.valueToTree(queryVector));
        knn.put("k", size);
        knn.put("num_candidates", Math.max(size * 2, 20));
        knn.set("filter", buildSecurityFilters(tenantId, allowedRoles));
        return objectMapper.writeValueAsString(root);
    }

    private String buildCreateIndexPayload(int vectorDimensions) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        properties.putObject("chunkId").put("type", "keyword");
        properties.putObject("documentId").put("type", "keyword");
        properties.putObject("documentName").put("type", "keyword");
        properties.putObject("tenantId").put("type", "keyword");
        properties.putObject("kbId").put("type", "keyword");
        properties.putObject("allowedRoles").put("type", "keyword");
        properties.putObject("chunkIndex").put("type", "integer");
        properties.putObject("content").put("type", "text");
        ObjectNode vector = properties.putObject("contentVector");
        vector.put("type", "dense_vector");
        vector.put("dims", vectorDimensions);
        vector.put("index", true);
        vector.put("similarity", "cosine");
        return objectMapper.writeValueAsString(root);
    }

    private ArrayNode sourceFields() {
        ArrayNode source = objectMapper.createArrayNode();
        source.add("chunkId");
        source.add("documentId");
        source.add("documentName");
        source.add("tenantId");
        source.add("kbId");
        source.add("allowedRoles");
        source.add("chunkIndex");
        source.add("content");
        return source;
    }

    private ArrayNode buildSecurityFilters(String tenantId, List<String> allowedRoles) {
        ArrayNode filters = objectMapper.createArrayNode();
        filters.addObject()
                .putObject("term")
                .put("tenantId", tenantId);

        ArrayNode rolesArray = objectMapper.createArrayNode();
        allowedRoles.forEach(rolesArray::add);
        filters.addObject()
                .putObject("terms")
                .set("allowedRoles", rolesArray);
        return filters;
    }
}
