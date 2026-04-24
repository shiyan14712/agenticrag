package com.yoswell.agenticrag.retrieval.document.dto.response;

/**
 * NER 命名实体表传输对象
 * @param mention 提到的概念
 * @param fullName 概念的全名
 * @param definition 概念的定义
 * @param category 概念的类别
 */
public record EntityRegistryEntryDTO(
        String mention,
        String fullName,
        String definition,
        String category) {
}
