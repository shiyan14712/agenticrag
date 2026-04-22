package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.IOException;
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

import com.yoswell.agenticrag.common.constants.KnowledgeChunkIndexConstants;
import com.yoswell.agenticrag.retrieval.document.entity.KnowledgeChunkDocumentDO;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkWriteService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 知识块 ES 写入服务实现
 *
 * <p>
 * 负责向量化产物的批量 index 和按文档 ID 的 delete-by-query，
 * 使用底层 REST Client 直接拼装 ES DSL 以保持显式可控
 * </p>
 */
@Service
public class KnowledgeChunkWriteServiceImpl implements KnowledgeChunkWriteService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkWriteServiceImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public KnowledgeChunkWriteServiceImpl(RestClient restClient,
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
    @Override
    public void indexChunks(List<KnowledgeChunkDocumentDO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        String documentId = chunks.get(0).documentId();
        log.info("[Offline RAG][ES_INDEX] 开始写入索引: index={}, documentId={}, chunkCount={}",
                indexName, documentId, chunks.size());

        ensureIndexExists(chunks.get(0).contentVector().size());

        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunkDocumentDO chunk = chunks.get(index);
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
     * 根据文档 ID 删除该文档在索引中的所有 chunk，具备租户级别的条件隔离
     *
     * @param documentId 文档业务 ID
     * @param tenantId   所属租户 ID
     */
    @Override
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
            log.info("[Offline RAG][ES_INDEX] Successfully deleted {} chunks from index {} for documentId: {}",
                    deletedCount, indexName, documentId);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == KnowledgeChunkIndexConstants.HTTP_NOT_FOUND) {
                log.warn("[Offline RAG][ES_INDEX] Index {} not found when trying to delete document: {}", indexName,
                        documentId);
            } else {
                throw new RuntimeException(
                        "[Offline RAG][ES_INDEX] ES Delete by query failed for document: " + documentId, e);
            }
        } catch (Exception e) {
            throw new RuntimeException("[Offline RAG][ES_INDEX] ES Delete by query failed for document: " + documentId,
                    e);
        }
    }

    /**
     * 确保索引存在，首次写入前按向量维度创建索引（双重检查锁）
     */
    public void ensureIndexExists(int vectorDimensions) {
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
     */
    private boolean indexExists() {
        try {
            Request request = new Request(KnowledgeChunkIndexConstants.METHOD_HEAD, indexPath());
            restClient.performRequest(request);
            return true;
        } catch (ResponseException exception) {
            return exception.getResponse().getStatusLine()
                    .getStatusCode() != KnowledgeChunkIndexConstants.HTTP_NOT_FOUND;
        } catch (IOException exception) {
            throw new RuntimeException("Failed to check Elasticsearch index existence", exception);
        }
    }

    /**
     * 创建用于知识块检索的索引结构
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

    private JsonNode executeForJson(Request request) throws IOException {
        return objectMapper.readTree(EntityUtils.toString(restClient.performRequest(request).getEntity()));
    }

    private boolean shouldLogIndexProgress(int indexedCount, int totalCount) {
        if (totalCount <= 5) {
            return true;
        }
        return indexedCount == 1 || indexedCount == totalCount || indexedCount % 50 == 0;
    }

    private String indexPath() {
        return "/" + indexName;
    }

    private String deleteByQueryPath() {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_DELETE_BY_QUERY;
    }

    private String documentPath(String chunkId) {
        return indexPath() + KnowledgeChunkIndexConstants.PATH_DOC_PREFIX + chunkId;
    }
}
