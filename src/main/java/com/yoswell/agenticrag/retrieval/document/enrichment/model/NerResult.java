package com.yoswell.agenticrag.retrieval.document.enrichment.model;

import java.util.List;

/**
 * LLM NER 结构化输出的 JSON Schema 映射
 *
 * @param entities 提取到的实体列表
 */
public record NerResult(List<Entity> entities) {

    /**
     * 单个实体
     *
     * @param mention 原文中的表述
     * @param fullName 完整名称
     * @param definition 简要定义
     * @param category 实体类别：PERSON, ORGANIZATION, LOCATION, ABBREVIATION, TERM, OTHER
     */
    public record Entity(
            String mention,
            String fullName,
            String definition,
            String category) {
    }
}
