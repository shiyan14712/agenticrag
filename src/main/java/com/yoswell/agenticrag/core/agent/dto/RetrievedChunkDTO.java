package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

/**
 * 召回知识块数据传输对象
 * 
 * <p>封装从 ElasticSearch 中检索出的单个知识块及其完整元数据信息。</p>
 * 
 * <p>该 DTO 是 RAG 混合检索（Hybrid Search）后召回的知识块标准化表示，
 * 包含文档归属、权限控制、内容片段和相似度评分等关键信息。</p>
 * 
 * <p>参考架构文档：CLAUDE.md - RAG 核心引擎模块</p>
 * 
 * @param chunkId 知识块 ID，ElasticSearch 中的唯一标识
 * @param documentId 所属业务文档 ID，对应 document_metadata.document_id
 * @param documentName 原始文件名，用于前端展示和溯源
 * @param tenantId 租户 ID，多租户隔离的关键字段
 * @param kbId 知识库 ID，标识知识块所属的逻辑知识库
 * @param allowedRoles 允许访问的角色列表，用于 ES 检索时的权限过滤
 * @param chunkIndex 块索引位置，同一文档中的顺序编号
 * @param content 知识块的文本内容，经过切分和预处理后的正文
 * @param score 当前阶段得分：通道检索阶段为 ES 原始 _score，RRF 阶段为 rank 融合分，
 *             reranker 阶段为 relevance_score
 * @author AgenticRAG
 * @since 2026-03-28
 */
public record RetrievedChunkDTO(
        String chunkId,
        String documentId,
        String documentName,
        String tenantId,
        String kbId,
        List<String> allowedRoles,
        int chunkIndex,
        String content,
        double score
) {
}
