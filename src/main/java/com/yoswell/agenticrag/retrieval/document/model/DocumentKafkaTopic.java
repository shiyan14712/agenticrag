package com.yoswell.agenticrag.retrieval.document.model;

/**
 * 文档异步链路的 Topic 语义枚举。
 *
 * <p>该枚举用于统一管理 Topic 语义标识与默认值，
 * 业务代码通过枚举取值，避免散落的字符串访问。</p>
 */
public enum DocumentKafkaTopic {

    /** 文档解析请求 Topic。 */
    PARSE_REQUEST("doc-parse-request"),

    /** 文档向量化请求 Topic。 */
    VECTORIZATION_REQUEST("doc-vectorize-request"),

    /** 文档删除请求 Topic。 */
    DELETE_REQUEST("doc-delete-request"),

    /** 死信 Topic。 */
    DEAD_LETTER("doc-dlq");

    private final String defaultTopicName;

    DocumentKafkaTopic(String defaultTopicName) {
        this.defaultTopicName = defaultTopicName;
    }

    public String defaultTopicName() {
        return defaultTopicName;
    }
}
