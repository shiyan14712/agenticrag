package com.yoswell.agenticrag.retrieval.document.entity;

import java.util.List;

/**
 * 写入 Elasticsearch 的知识块文档模型。
 *
 * @param chunkId       chunk 唯一标识
 * @param documentId    所属文档 ID
 * @param documentName  所属文档名称
 * @param tenantId      所属租户
 * @param kbId          所属知识库
 * @param allowedRoles  可以访问该 chunk 的角色列表
 * @param chunkIndex    chunk 在文档中的顺序
 * @param content       原始文本内容
 * @param contentVector 对应的向量表示
 */
public record KnowledgeChunkDocumentDO(
                String chunkId,
                String documentId,
                String documentName,
                String tenantId,
                String kbId,
                List<String> allowedRoles,
                int chunkIndex,
                String content,
                List<Float> contentVector) {
}
