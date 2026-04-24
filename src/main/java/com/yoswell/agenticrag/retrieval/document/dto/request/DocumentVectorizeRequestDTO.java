package com.yoswell.agenticrag.retrieval.document.dto.request;

import java.util.List;

/**
 * 文档进入向量化阶段时使用的消息体。
 *
 * <p>它和解析消息拥有相近字段，但语义不同：这里表达的是
 * “当前文档可以继续进行切块、embedding 和索引写入”。</p>
 *
 * @param documentId 业务侧文档唯一标识
 * @param tenantId 文档所属租户
 * @param kbId 文档所属知识库
 * @param fileName 原始文件名
 * @param fileUrl 文件在对象存储中的地址
 * @param fileExtension 文件扩展名，用于选择解析策略
 * @param allowedRoles 文档允许访问的角色列表
 * @param chunkingStrategy 分块策略：STANDARD / DECONTEXTUALISED / QA_ENRICHED
 * @param messageId 业务幂等消息 ID
 * @param timestamp 消息创建时间戳，用于链路追踪
 */
public record DocumentVectorizeRequestDTO(
        String documentId,
        String tenantId,
        String kbId,
        String fileName,
        String fileUrl,
        String fileExtension,
        List<String> allowedRoles,
        String chunkingStrategy,
        String messageId,
        long timestamp
) {
}
