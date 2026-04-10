package com.yoswell.agenticrag.retrieval.document.reliability.model;

/**
 * 文档异步任务类型枚举
 * <p>
 * 定义文档处理链路中三类核心异步任务类型，对应不同的 Kafka Topic 与业务处理流程。
 * 作为 {@code document_async_task} 表的 {@code task_type} 字段取值，用于区分任务归属的处理阶段。
 * </p>
 *
 * <ul>
 *   <li>{@link #DOCUMENT_PARSE} - 对应 {@code doc-parse-request} Topic，触发 MinerU 深度版面分析</li>
 *   <li>{@link #DOCUMENT_VECTORIZATION} - 对应 {@code doc-vectorize-request} Topic，触发离线 Chunking 与向量入库</li>
 *   <li>{@link #DOCUMENT_DELETE} - 对应 {@code doc-delete-request} Topic，触发 ES 向量清理与元数据软删除</li>
 * </ul>
 *
 * @see com.yoswell.agenticrag.common.config.KafkaConfig
 */
public enum DocumentAsyncTaskType {
    /**
     * 文档解析任务：将上传至 MinIO 的原始文件交由 Python MinerU Worker 进行高精度版面分析与 Markdown 提取
     */
    DOCUMENT_PARSE,

    /**
     * 文档向量化任务：读取 MinIO 中的解析结果，执行策略化分块、Embedding 计算并写入 ElasticSearch
     */
    DOCUMENT_VECTORIZATION,

    /**
     * 文档删除任务：清理 ElasticSearch 中的向量分块、MinIO 中的物理文件及 MySQL 中的元数据记录
     */
    DOCUMENT_DELETE;

    /**
     * 返回枚举值的字符串表示（与数据库存储值及 Kafka Topic 映射关系保持一致）
     *
     * @return 枚举名称字符串
     */
    public String value() {
        return name();
    }
}
