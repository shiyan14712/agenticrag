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

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.retrieval.document.index.constants.KnowledgeChunkIndexConstants;
import com.yoswell.agenticrag.retrieval.document.index.dto.KnowledgeChunkSearchHitDTO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 负责知识块在 Elasticsearch 中的索引写入与检索
 *
 * <p>这里直接使用底层 REST Client 拼装请求，目的是让索引结构和查询 DSL
 * 尽量保持显式可控</p>
 */
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

    /**
     * 批量写入知识块到 Elasticsearch
     *
     * @param chunks 需要写入的知识块集合
     */
    public void indexChunks(List<KnowledgeChunkDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        String documentId = chunks.get(0).documentId();
        log.info("[Offline RAG][ES_INDEX] 开始写入索引: index={}, documentId={}, chunkCount={}",
                indexName, documentId, chunks.size());

        ensureIndexExists(chunks.get(0).contentVector().size());

        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunkDocument chunk = chunks.get(index);
            try {
                Request request = new Request(KnowledgeChunkIndexConstants.METHOD_PUT, documentPath(chunk.chunkId()));
                request.setJsonEntity(objectMapper.writeValueAsString(chunk));
                restClient.performRequest(request);

                int indexedCount = index + 1;
                if (shouldLogIndexProgress(indexedCount, chunks.size())) {
                    log.info("[Offline RAG][ES_INDEX] 写入进度: documentId={}, {}/{}, chunkId={}",
                            documentId, indexedCount, chunks.size(), chunk.chunkId());
                }
            } catch (Exception exception) {
                log.error("[Offline RAG][ES_INDEX] 写入失败: index={}, documentId={}, chunkId={}",
                        indexName, documentId, chunk.chunkId(), exception);
                throw new RuntimeException("Failed to index knowledge chunk " + chunk.chunkId(), exception);
            }
        }

        log.info("[Offline RAG][ES_INDEX] 索引写入完成: index={}, documentId={}, chunkCount={}",
                indexName, documentId, chunks.size());
    }

    /**
     * 根据文档 ID 删除该文档产生的所有向量与分块
     * 具备租户级别的条件隔离
     *
     * @param documentId 文档业务 ID
     * @param tenantId 当前所属租户
     */
    public void deleteByDocumentId(String documentId, String tenantId) {
        if (documentId == null || tenantId == null) {
             throw new IllegalArgumentException("documentId and tenantId must not be null");
        }

        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_POST, deleteByQueryPath());
            
            ObjectNode queryNode = objectMapper.createObjectNode();
            ObjectNode boolNode = queryNode.putObject(KnowledgeChunkIndexConstants.JSON_BOOL);
            ArrayNode filterArray = boolNode.putArray(KnowledgeChunkIndexConstants.JSON_FILTER);

            filterArray.addObject()
                    .putObject(KnowledgeChunkIndexConstants.JSON_TERM)
                    .put(KnowledgeChunkIndexConstants.FIELD_DOCUMENT_ID_KEYWORD, documentId);
            filterArray.addObject()
                    .putObject(KnowledgeChunkIndexConstants.JSON_TERM)
                    .put(KnowledgeChunkIndexConstants.FIELD_TENANT_ID_KEYWORD, tenantId);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.set(KnowledgeChunkIndexConstants.JSON_QUERY, queryNode);

            request.setJsonEntity(objectMapper.writeValueAsString(payload));
            
            JsonNode response = executeForJson(request);
            long deletedCount = response.path(KnowledgeChunkIndexConstants.JSON_DELETED).asLong();
            log.info("Successfully deleted {} chunks from index {} for documentId: {}", deletedCount, indexName, documentId);
        } catch (ResponseException e) {
             if (e.getResponse().getStatusLine().getStatusCode() == KnowledgeChunkIndexConstants.HTTP_NOT_FOUND) {
                 log.warn("Index {} not found when trying to delete document: {}", indexName, documentId);
             } else {
                 throw new RuntimeException("ES Delete by query failed for document: " + documentId, e);
             }
        } catch (Exception e) {
             throw new RuntimeException("ES Delete by query failed for document: " + documentId, e);
        }
    }

    /**
     * 通过关键词执行 BM25 检索
     *
     * @param query 查询文本
     * @param tenantId 当前租户
     * @param allowedRoles 调用方允许访问的角色
     * @param size 返回数量
     * @return 检索结果
     */
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
     * 通过向量执行 kNN 检索
     *
     * @param queryVector 查询向量
     * @param tenantId 当前租户
     * @param allowedRoles 调用方允许访问的角色
     * @param size 返回数量
     * @return 检索结果
     */
    public List<RetrievedChunkDTO> searchByVector(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size) {
        if (queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }

        ensureIndexExists(queryVector.size());
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_POST, searchPath());
            request.setJsonEntity(buildVectorSearchPayload(queryVector, tenantId, allowedRoles, size));
            JsonNode response = executeForJson(request);
            return parseSearchHits(response);
        } catch (Exception exception) {
            throw new RuntimeException("Vector search failed", exception);
        }
    }

    /**
     * 确保索引存在，并在首次写入前按向量维度创建索引
     *
     * @param vectorDimensions 向量维度
     */
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

    /**
     * 检查目标索引是否已经存在
     *
     * @return true 表示索引存在
     */
    private boolean indexExists() {
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_HEAD, indexPath());
            restClient.performRequest(request);
            return true;
        } catch (ResponseException exception) {
            return exception.getResponse().getStatusLine().getStatusCode() != KnowledgeChunkIndexConstants.HTTP_NOT_FOUND;
        } catch (IOException exception) {
            throw new RuntimeException("Failed to check Elasticsearch index existence", exception);
        }
    }

    /**
     * 创建用于知识块检索的索引结构
     *
     * @param vectorDimensions 向量维度
     */
    private void createIndex(int vectorDimensions) {
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_PUT, indexPath());
            request.setJsonEntity(buildCreateIndexPayload(vectorDimensions));
            restClient.performRequest(request);
            log.info("Created Elasticsearch chunk index: {}", indexName);
        } catch (ResponseException exception) {
            int statusCode = exception.getResponse().getStatusLine().getStatusCode();
            if (statusCode != KnowledgeChunkIndexConstants.HTTP_BAD_REQUEST) {
                throw new RuntimeException("Failed to create Elasticsearch index", exception);
            }
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create Elasticsearch index", exception);
        }
    }

    /**
     * 执行请求并把响应解析为 JSON 树
     *
     * @param request REST 请求
     * @return JSON 响应
     * @throws IOException 当底层 HTTP 失败时抛出
     */
    private JsonNode executeForJson(Request request) throws IOException {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    /**
     * 把 Elasticsearch 返回的 hits 数组转换为内部检索结果
     *
     * @param response Elasticsearch 查询响应
     * @return 标准化后的检索结果
     */
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

    /**
     * 构造关键词检索请求体
     *
     * @param query 查询文本
     * @param tenantId 当前租户
     * @param allowedRoles 当前允许访问的角色
     * @param size 返回数量
     * @return JSON 请求体
     * @throws IOException 当序列化失败时抛出
     */
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

    /**
     * 构造向量检索请求体
     *
     * @param queryVector 查询向量
     * @param tenantId 当前租户
     * @param allowedRoles 当前允许访问的角色
     * @param size 返回数量
     * @return JSON 请求体
     * @throws IOException 当序列化失败时抛出
     */
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

    private boolean shouldLogIndexProgress(int indexedCount, int totalCount) {
        if (totalCount <= 5) {
            return true;
        }
        return indexedCount == 1 || indexedCount == totalCount || indexedCount % 50 == 0;
    }

    /**
     * 构造索引 mappings，请求中显式声明文本字段、权限字段和 dense_vector 字段
     *
     * @param vectorDimensions 向量维度
     * @return JSON 请求体
     * @throws IOException 当序列化失败时抛出
     */
    private String buildCreateIndexPayload(int vectorDimensions) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root
            .putObject(KnowledgeChunkIndexConstants.JSON_MAPPINGS)
            .putObject(KnowledgeChunkIndexConstants.JSON_PROPERTIES);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_CHUNK_ID)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_DOCUMENT_ID)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_DOCUMENT_NAME)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_TENANT_ID)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_KB_ID)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_ALLOWED_ROLES)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_KEYWORD);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_CHUNK_INDEX)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_INTEGER);
        properties.putObject(KnowledgeChunkIndexConstants.FIELD_CONTENT)
            .put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_TEXT);
        ObjectNode vector = properties.putObject(KnowledgeChunkIndexConstants.FIELD_CONTENT_VECTOR);
        vector.put(KnowledgeChunkIndexConstants.JSON_TYPE, KnowledgeChunkIndexConstants.TYPE_DENSE_VECTOR);
        vector.put(KnowledgeChunkIndexConstants.JSON_DIMS, vectorDimensions);
        vector.put(KnowledgeChunkIndexConstants.JSON_INDEX, true);
        vector.put(KnowledgeChunkIndexConstants.JSON_SIMILARITY, KnowledgeChunkIndexConstants.SIMILARITY_COSINE);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 定义查询结果中需要回传的字段，避免加载整个源文档
     *
     * @return source 字段数组
     */
    private ArrayNode sourceFields() {
        ArrayNode source = objectMapper.createArrayNode();
        KnowledgeChunkIndexConstants.DEFAULT_SOURCE_FIELDS.forEach(source::add);
        return source;
    }

    /**
     * 构造租户和角色维度的安全过滤条件
     *
     * @param tenantId 当前租户
     * @param allowedRoles 当前允许访问的角色
     * @return ES filter 数组
     */
    private ArrayNode buildSecurityFilters(String tenantId, List<String> allowedRoles) {
        ArrayNode filters = objectMapper.createArrayNode();
        filters.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERM)
                .put(KnowledgeChunkIndexConstants.FIELD_TENANT_ID, tenantId);

        ArrayNode rolesArray = objectMapper.createArrayNode();
        allowedRoles.forEach(rolesArray::add);
        filters.addObject()
                .putObject(KnowledgeChunkIndexConstants.JSON_TERMS)
                .set(KnowledgeChunkIndexConstants.FIELD_ALLOWED_ROLES, rolesArray);
        return filters;
    }

    private String indexPath() {
        return "/" + indexName;
    }

    private String searchPath() {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_SEARCH;
    }

    private String deleteByQueryPath() {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_DELETE_BY_QUERY;
    }

    private String documentPath(String chunkId) {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_DOC_PREFIX + chunkId;
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
}
