package com.yoswell.agenticrag.retrieval.document.enrichment.model;

import java.util.List;

/**
 * LLM 为模糊信息单元生成补充上下文的 JSON Schema 映射
 *
 * @param contextStatements 补充上下文陈述句列表
 */
public record SupplementaryContextResult(List<ContextStatement> contextStatements) {

    /**
     * 单条补充上下文
     *
     * @param mention 对应的模糊表述
     * @param statement 补充陈述句
     */
    public record ContextStatement(
            String mention,
            String statement) {
    }
}
