package com.yoswell.agenticrag.retrieval.document.enrichment.model;

/**
 * 实体注册表中的单条实体记录
 *
 * @param mention 原文中的表述（如 "Bird"）
 * @param fullName 完整名称（如 "California scooter sharing start-up Bird"）
 * @param definition 简要定义或描述
 * @param category 实体类别：PERSON, ORGANIZATION, LOCATION, ABBREVIATION, TERM, OTHER
 */
public record EntityRegistryEntry(
        String mention,
        String fullName,
        String definition,
        String category) {
}
