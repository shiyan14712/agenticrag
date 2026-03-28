package com.yoswell.agenticrag.retrieval.document.dto.request;

/**
 * 文档删除请求消息体。
 *
 * 用于跨系统异步通知底层存储（如 Elasticsearch）彻底抹除该文档对应的所有的向量与分块数据，保持 RAG 环境的干净。
 *
 * @param documentId 文档业务 ID
 * @param tenantId 当前租户 ID（用于安全双检与隔离）
 * @param timestamp 消息投递时间戳
 */
public record DocumentDeleteRequestDTO(
        String documentId,
        String tenantId,
        long timestamp
) {
}