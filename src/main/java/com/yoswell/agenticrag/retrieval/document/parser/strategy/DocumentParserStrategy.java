package com.yoswell.agenticrag.retrieval.document.parser.strategy;

import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;

/**
 * 文档解析策略统一接口。
 */
public interface DocumentParserStrategy {

    /**
     * 将原始文本源转换为标准化解析结果。
     *
     * @param source 已读取完成的解析输入
     * @return 解析后的文档与 chunk 集合
     */
    ParsedDocument parse(DocumentParseSource source);
}
