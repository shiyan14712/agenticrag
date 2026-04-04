package com.yoswell.agenticrag.common.constants;

import java.util.List;

/**
 * Knowledge chunk Elasticsearch 相关常量。
 */
public final class KnowledgeChunkIndexConstants {

    private KnowledgeChunkIndexConstants() {
        // utility class
    }

    public static final String METHOD_HEAD = "HEAD";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_PUT = "PUT";

    public static final String PATH_SEARCH = "/_search";
    public static final String PATH_DOC_PREFIX = "/_doc/";
    public static final String PATH_DELETE_BY_QUERY = "/_delete_by_query";

    public static final String JSON_HITS = "hits";
    public static final String JSON_SOURCE = "_source";
    public static final String JSON_ID = "_id";
    public static final String JSON_SCORE = "_score";
    public static final String JSON_DELETED = "deleted";

    public static final String JSON_QUERY = "query";
    public static final String JSON_BOOL = "bool";
    public static final String JSON_FILTER = "filter";
    public static final String JSON_MUST = "must";
    public static final String JSON_MATCH = "match";
    public static final String JSON_TERM = "term";
    public static final String JSON_TERMS = "terms";
    public static final String JSON_SIZE = "size";
    public static final String JSON_SOURCE_FIELDS = "_source";

    public static final String JSON_KNN = "knn";
    public static final String JSON_FIELD = "field";
    public static final String JSON_QUERY_VECTOR = "query_vector";
    public static final String JSON_K = "k";
    public static final String JSON_NUM_CANDIDATES = "num_candidates";

    public static final String JSON_MAPPINGS = "mappings";
    public static final String JSON_PROPERTIES = "properties";
    public static final String JSON_TYPE = "type";
    public static final String JSON_DIMS = "dims";
    public static final String JSON_INDEX = "index";
    public static final String JSON_SIMILARITY = "similarity";

    public static final String FIELD_CHUNK_ID = "chunkId";
    public static final String FIELD_DOCUMENT_ID = "documentId";
    public static final String FIELD_DOCUMENT_NAME = "documentName";
    public static final String FIELD_TENANT_ID = "tenantId";
    public static final String FIELD_KB_ID = "kbId";
    public static final String FIELD_ALLOWED_ROLES = "allowedRoles";
    public static final String FIELD_CHUNK_INDEX = "chunkIndex";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CONTENT_VECTOR = "contentVector";

    // documentId/tenantId 字段本身就是 keyword 类型，无需 .keyword 子字段
    public static final String FIELD_DOCUMENT_ID_KEYWORD = FIELD_DOCUMENT_ID;
    public static final String FIELD_TENANT_ID_KEYWORD = FIELD_TENANT_ID;

    public static final String TYPE_KEYWORD = "keyword";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_DENSE_VECTOR = "dense_vector";
    public static final String SIMILARITY_COSINE = "cosine";

    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_BAD_REQUEST = 400;

    public static final int KNN_MIN_NUM_CANDIDATES = 20;
    public static final int KNN_CANDIDATES_MULTIPLIER = 2;

    public static final double DEFAULT_SCORE = 0.0d;

    public static final List<String> DEFAULT_SOURCE_FIELDS = List.of(
            FIELD_CHUNK_ID,
            FIELD_DOCUMENT_ID,
            FIELD_DOCUMENT_NAME,
            FIELD_TENANT_ID,
            FIELD_KB_ID,
            FIELD_ALLOWED_ROLES,
            FIELD_CHUNK_INDEX,
            FIELD_CONTENT
    );
}