package com.yoswell.agenticrag.retrieval.document.model;

/**
 * 文档分块策略枚举，由用户在上传时指定
 */
public enum ChunkingStrategy {

    /** 常规结构化切分（MarkdownStrategy / StandardTxtStrategy） */
    STANDARD,

    /** 去上下文化改写：LLM 改写 chunk 使其脱离原文可独立理解 */
    DECONTEXTUALISED,

    /** QA 增强：保留原文，追加 LLM 生成的补充上下文 */
    QA_ENRICHED;

    public String value() {
        return name();
    }
}
