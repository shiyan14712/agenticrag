package com.yoswell.agenticrag.retrieval.document.reliability.model;

/**
 * 文档异步任务状态枚举
 * <p>
 * 作为文档异步动作的业务账本，维护文档处理链路中各阶段任务的完整生命周期。
 * 对应数据库表 {@code document_async_task} 的 {@code status} 字段。
 * </p>
 *
 * @see DocumentAsyncTaskType
 */
public enum DocumentAsyncTaskStatus {
    /**
     * 待派发：任务已创建但尚未被后台 dispatcher 扫描处理
     */
    PENDING,

    /**
     * 已派发：任务已被 dispatcher 选中并投递至 Kafka，等待消费者执行
     */
    DISPATCHED,

    /**
     * 执行中：消费者已获取消息并开始处理（用于并发抢占与租约控制）
     */
    RUNNING,

    /**
     * 成功：任务已成功完成（如向量化入库、解析完成等）
     */
    SUCCEEDED,

    /**
     * 失败：任务执行失败且已达到最大重试次数，进入终态需人工介入或告警
     */
    FAILED,

    /**
     * 跳过：任务因幂等防护或前置条件不满足而被安全跳过（如文档已向量化完成）
     */
    SKIPPED;

    /**
     * 返回枚举值的字符串表示（与数据库存储值保持一致）
     *
     * @return 枚举名称字符串
     */
    public String value() {
        return name();
    }
}
