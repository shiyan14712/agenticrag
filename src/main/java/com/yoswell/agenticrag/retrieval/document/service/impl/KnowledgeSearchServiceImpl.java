package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.common.constants.KnowledgeChunkIndexConstants;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.retrieval.document.dto.KnowledgeChunkSearchHitDTO;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeSearchService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 知识块 ES 检索服务实现
 *
 * <p>提供 BM25 关键词检索与 kNN 向量检索，每次请求均强制施加租户隔离与角色权限过滤</p>
 */
@Service
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchServiceImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String indexName;

    public KnowledgeSearchServiceImpl(RestClient restClient,
                                      ObjectMapper objectMapper,
                                      @Value("${rag.index.name:enterprise_chunks}") String indexName) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.indexName = indexName;
    }

    /**
     * 通过关键词执行 BM25 全文检索
     *
     * @param query        查询文本
     * @param tenantId     当前租户 ID
     * @param allowedRoles 调用方允许访问的角色列表
     * @param size         最大返回数量
     * @return 检索结果
     */
    @Override
    public List<RetrievedChunkDTO> searchByKeyword(String query, String tenantId, List<String> allowedRoles, int size) {
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_POST, searchPath());
            request.setJsonEntity(buildKeywordSearchPayload(query, tenantId, allowedRoles, size));
            JsonNode response = executeForJson(request);
            return parseSearchHits(response);
        } catch (Exception exception) {
            throw new RuntimeException("Keyword search failed", exception);
        }
    }

    /**
     * 通过稠密向量执行 kNN 相似度检索
     *
     * @param queryVector  查询向量
     * @param tenantId     当前租户 ID
     * @param allowedRoles 调用方允许访问的角色列表
     * @param size         最大返回数量
     * @return 检索结果
     */
    @Override
    public List<RetrievedChunkDTO> searchByVector(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size) {
        if (queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_POST, searchPath());
            request.setJsonEntity(buildVectorSearchPayload(queryVector, tenantId, allowedRoles, size));
            JsonNode response = executeForJson(request);
            return parseSearchHits(response);
        } catch (Exception exception) {
            throw new RuntimeException("Vector search failed", exception);
        }
    }

    private String buildKeywordSearchPayload(String query, String tenantId, List<String> allowedRoles, int size) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(KnowledgeChunkIndexConstants.JSON_SIZE, size);
        root.set(KnowledgeChunkIndexConstants.JSON_SOURCE_FIELDS, sourceFields());

        ObjectNode boolNode = root.putObject(KnowledgeChunkIndexConstants.JSON_QUERY)
            .putObject(KnowledgeChunkIndexConstants.JSON_BOOL);
        ArrayNode must = boolNode.putArray(KnowledgeChunkIndexConstants.JSON_MUST);
        must.addObject()
            .putObject(KnowledgeChunkIndexConstants.JSON_MATCH)
            .putObject(KnowledgeChunkIndexConstants.FIELD_CONTENT)
            .put(KnowledgeChunkIndexConstants.JSON_QUERY, query);
        boolNode.set(KnowledgeChunkIndexConstants.JSON_FILTER, buildSecurityFilters(tenantId, allowedRoles));
        return objectMapper.writeValueAsString(root);
    }

    private String buildVectorSearchPayload(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(KnowledgeChunkIndexConstants.JSON_SIZE, size);
        root.set(KnowledgeChunkIndexConstants.JSON_SOURCE_FIELDS, sourceFields());

        ObjectNode knn = root.putObject(KnowledgeChunkIndexConstants.JSON_KNN);
        knn.put(KnowledgeChunkIndexConstants.JSON_FIELD, KnowledgeChunkIndexConstants.FIELD_CONTENT_VECTOR);
        knn.set(KnowledgeChunkIndexConstants.JSON_QUERY_VECTOR, objectMapper.valueToTree(queryVector));
        knn.put(KnowledgeChunkIndexConstants.JSON_K, size);
        knn.put(
                KnowledgeChunkIndexConstants.JSON_NUM_CANDIDATES,
                Math.max(size * KnowledgeChunkIndexConstants.KNN_CANDIDATES_MULTIPLIER,
                        KnowledgeChunkIndexConstants.KNN_MIN_NUM_CANDIDATES)
        );
        knn.set(KnowledgeChunkIndexConstants.JSON_FILTER, buildSecurityFilters(tenantId, allowedRoles));
        return objectMapper.writeValueAsString(root);
    }

    private List<RetrievedChunkDTO> parseSearchHits(JsonNode response) {
        JsonNode hits = response.path(KnowledgeChunkIndexConstants.JSON_HITS).path(KnowledgeChunkIndexConstants.JSON_HITS);
        if (!hits.isArray() || hits.isEmpty()) {
            return List.of();
        }

        ArrayList<RetrievedChunkDTO> chunks = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            JsonNode source = hit.path(KnowledgeChunkIndexConstants.JSON_SOURCE);
            KnowledgeChunkSearchHitDTO sourceDto = objectMapper.convertValue(source, KnowledgeChunkSearchHitDTO.class);

            chunks.add(new RetrievedChunkDTO(
                    firstNonBlank(sourceDto.chunkId(), textValueOrEmpty(hit.path(KnowledgeChunkIndexConstants.JSON_ID))),
                    defaultString(sourceDto.documentId()),
                    defaultString(sourceDto.documentName()),
                    defaultString(sourceDto.tenantId()),
                    defaultString(sourceDto.kbId()),
                    sourceDto.allowedRoles() == null ? List.of() : List.copyOf(sourceDto.allowedRoles()),
                    sourceDto.chunkIndex(),
                    defaultString(sourceDto.content()),
                    hit.path(KnowledgeChunkIndexConstants.JSON_SCORE).asDouble(KnowledgeChunkIndexConstants.DEFAULT_SCORE)
            ));
        }
        return List.copyOf(chunks);
    }

    private ArrayNode buildSecurityFilters(String tenantId, List<String> allowedRoles) {
        ArrayNode filters = objectMapper.createArrayNode();
        filters.add(buildTenantFilter(tenantId));
        filters.add(buildAllowedRolesFilter(allowedRoles));
        return filters;
    }

    private ObjectNode buildTenantFilter(String tenantId) {
        ObjectNode tenantFilter = objectMapper.createObjectNode();
        ObjectNode boolNode = tenantFilter.putObject(KnowledgeChunkIndexConstants.JSON_BOOL);
        ArrayNode should = boolNode.putArray(KnowledgeChunkIndexConstants.JSON_SHOULD);
        should.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERM)
                .put(KnowledgeChunkIndexConstants.FIELD_TENANT_ID, tenantId);
        should.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERM)
                .put(KnowledgeChunkIndexConstants.FIELD_TENANT_ID_DOT_KEYWORD, tenantId);
        boolNode.put(KnowledgeChunkIndexConstants.JSON_MINIMUM_SHOULD_MATCH, 1);
        return tenantFilter;
    }

    private ObjectNode buildAllowedRolesFilter(List<String> allowedRoles) {
        List<String> normalizedRoles = normalizeRoleFilters(allowedRoles);
        ObjectNode roleFilter = objectMapper.createObjectNode();
        ObjectNode boolNode = roleFilter.putObject(KnowledgeChunkIndexConstants.JSON_BOOL);
        ArrayNode should = boolNode.putArray(KnowledgeChunkIndexConstants.JSON_SHOULD);
        should.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERMS)
                .set(KnowledgeChunkIndexConstants.FIELD_ALLOWED_ROLES, toArrayNode(normalizedRoles));
        should.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERMS)
                .set(KnowledgeChunkIndexConstants.FIELD_ALLOWED_ROLES_DOT_KEYWORD, toArrayNode(normalizedRoles));
        boolNode.put(KnowledgeChunkIndexConstants.JSON_MINIMUM_SHOULD_MATCH, 1);
        return roleFilter;
    }

    private List<String> normalizeRoleFilters(List<String> allowedRoles) {
        LinkedHashSet<String> roleFilters = new LinkedHashSet<>();
        if (allowedRoles != null) {
            for (String role : allowedRoles) {
                if (role == null) {
                    continue;
                }
                String trimmed = role.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                roleFilters.add(trimmed);
                roleFilters.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        if (roleFilters.isEmpty()) {
            roleFilters.add("__NO_ROLE__");
        }
        return List.copyOf(roleFilters);
    }

    private ArrayNode toArrayNode(List<String> values) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        values.forEach(arrayNode::add);
        return arrayNode;
    }

    private JsonNode executeForJson(Request request) throws IOException {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    private ArrayNode sourceFields() {
        ArrayNode source = objectMapper.createArrayNode();
        KnowledgeChunkIndexConstants.DEFAULT_SOURCE_FIELDS.forEach(source::add);
        return source;
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String textValueOrEmpty(JsonNode node) {
        if (node == null) {
            return "";
        }
        String text = objectMapper.convertValue(node, String.class);
        return text == null ? "" : text;
    }

    private String indexPath() {
        return "/" + indexName;
    }

    private String searchPath() {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_SEARCH;
    }
}
