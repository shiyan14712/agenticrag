package com.yoswell.agenticrag.retrieval.document.enrichment.model;

import java.util.List;

/**
 * LLM 模糊信息单元识别的 JSON Schema 映射（QA-Enriched 专用）
 *
 * @param ambiguousUnits 识别到的模糊信息单元列表
 */
public record AmbiguousUnitResult(List<AmbiguousUnit> ambiguousUnits) {

    /**
     * 单个模糊信息单元
     *
     * @param mention 原文中的模糊表述
     * @param reason 判定为模糊的原因
     */
    public record AmbiguousUnit(
            String mention,
            String reason) {
    }
}
