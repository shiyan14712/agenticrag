package com.yoswell.agenticrag.retrieval.document.model;

/**
 * 文档异步处理链路中的状态枚举
 */
public enum DocumentProcessingStatus {
    /** 文件已上传并完成元数据落库 */
    UPLOADED,
    /** 正在做解析、切块或其它前置处理 */
    PARSING,
    /** 已完成向量化并写入检索索引 */
    VECTORIZED,
    /** 处理链路失败，需要人工排查或重试 */
    FAILED;

    /**
     * 返回适合持久化的字符串值
     *
     * @return 枚举名称
     */
    public String value() {
        return name();
    }
}
