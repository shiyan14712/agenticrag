package com.yoswell.agenticrag.retrieval.document.parser.model;

/**
 * 文档解析阶段生成的单个文本块。
 *
 * @param chunkId chunk 唯一标识
 * @param chunkIndex chunk 在文档内的顺序
 * @param content 当前 chunk 的文本内容
 */
public record ParsedDocumentChunk(
        String chunkId,
        int chunkIndex,
        String content) {
}
