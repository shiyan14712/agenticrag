package com.yoswell.agenticrag.retrieval.document.enrichment.model;

/**
 * Special Chunking 产出的增强 chunk
 *
 * @param chunkId chunk 唯一标识（沿用常规切分阶段生成的 ID）
 * @param chunkIndex chunk 在文档内的顺序
 * @param originalContent 原始 chunk 文本（保留用于 LLM 引用原文）
 * @param enrichedContent 增强后的文本（用于 embedding 和检索）
 */
public record EnrichedChunk(
        String chunkId,
        int chunkIndex,
        String originalContent,
        String enrichedContent) {
}
