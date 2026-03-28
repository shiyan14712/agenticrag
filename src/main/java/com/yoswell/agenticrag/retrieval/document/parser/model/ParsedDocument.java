package com.yoswell.agenticrag.retrieval.document.parser.model;

import java.util.List;

/**
 * 文档解析完成后的标准化结果。
 *
 * @param sourceUrl 原始文件地址
 * @param sourceName 原始文件名
 * @param chunks 解析后得到的切块结果，顺序即原文顺序
 */
public record ParsedDocument(
        String sourceUrl,
        String sourceName,
        List<ParsedDocumentChunk> chunks) {
}
