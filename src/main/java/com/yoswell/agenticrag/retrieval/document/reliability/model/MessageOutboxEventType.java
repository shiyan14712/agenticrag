package com.yoswell.agenticrag.retrieval.document.reliability.model;

/**
 * 事务型 Outbox 业务事件类型枚举。
 *
 * <p>对应数据库表 {@code mq_outbox} 的 {@code event_type} 字段，限定可落库的事件值，
 * 避免字符串硬编码导致脏数据。</p>
 */
public enum MessageOutboxEventType {

    /** 文档解析请求事件。 */
    DOCUMENT_PARSE_REQUEST,

    /** 文档向量化请求事件。 */
    DOCUMENT_VECTORIZATION_REQUEST,

    /** 文档删除请求事件。 */
    DOCUMENT_DELETE_REQUEST;

    /**
     * 返回枚举值的字符串表示（与数据库存储值保持一致）。
     *
     * @return 枚举名称字符串
     */
    public String value() {
        return name();
    }
}