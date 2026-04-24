package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

/**
 * 召回知识块数据传输对象
 *
 * @param chunkId 知识块 ID
 * @param documentId 所属业务文档 ID
 * @param documentName 原始文件名
 * @param tenantId 租户 ID
 * @param kbId 知识库 ID
 * @param allowedRoles 允许访问的角色列表
 * @param chunkIndex 块索引位置
 * @param content 用于检索的文本内容（enrichedContent 或原文）
 * @param originalContent 原始文本内容（Special Chunk 时保留原文，STANDARD 时与 content 一致）
 * @param chunkingStrategy 分块策略标记
 * @param score 当前阶段得分
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
        String originalContent,
        String chunkingStrategy,
        double score
) {
}
