package com.yoswell.agenticrag.retrieval.document.reliability.model;

/**
 * 事务型 Outbox 消息状态枚举
 * <p>
 * 用于实现「数据库提交与消息投递最终一致性」的事务型 Outbox 模式。
 * 对应数据库表 {@code mq_outbox} 的 {@code status} 字段，由后台 dispatcher 周期性扫描并驱动状态流转。
 * </p>
 *
 * <p><b>状态流转路径：</b></p>
 * <pre>
 * PENDING -> DISPATCHING -> SENT (成功终态)
 *                    \-> FAILED (触发指数退避重试，更新 next_retry_at)
 * </pre>
 *
 * @see com.yoswell.agenticrag.retrieval.document.reliability.entity.MessageOutbox
 */
public enum MessageOutboxStatus {
    /**
     * 待发送：事务内已写入 outbox 表但尚未被 dispatcher 扫描处理
     */
    PENDING,

    /**
     * 发送中：dispatcher 已选中该记录并尝试投递至 Kafka（用于防止并发重复发送）
     */
    DISPATCHING,

    /**
     * 已发送：Kafka 投递成功，消息已进入消息队列等待消费者处理
     */
    SENT,

    /**
     * 发送失败：Kafka 投递异常，dispatcher 将按指数退避策略更新 {@code next_retry_at} 并重新排队
     */
    FAILED;

    /**
     * 返回枚举值的字符串表示（与数据库存储值保持一致）
     *
     * @return 枚举名称字符串
     */
    public String value() {
        return name();
    }
}
