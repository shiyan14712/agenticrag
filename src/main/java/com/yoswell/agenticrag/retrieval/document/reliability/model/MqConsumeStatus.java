package com.yoswell.agenticrag.retrieval.document.reliability.model;

/**
 * Kafka 消息消费日志状态枚举
 * <p>
 * 用于关键消费链路（如 {@code doc-vectorize-request}、{@code doc-delete-request}、{@code doc-dlq}）
 * 的防重复消费与可靠留痕。对应数据库表 {@code mq_consume_log} 的 {@code status} 字段。
 * </p>
 *
 * <p><b>设计要点：</b></p>
 * <ul>
 *   <li>基于 {@code consumer_group + topic + message_identity} 构建唯一幂等键</li>
 *   <li>通过 {@code locked_until} 租约字段防止并发消费者同时处理同一条消息</li>
 *   <li>区分「正在处理」({@link #PROCESSING}) 与「已处理完成」({@link #SUCCEEDED}/{@link #FAILED}) 状态</li>
 * </ul>
 *
 * @see com.yoswell.agenticrag.retrieval.document.reliability.entity.MqConsumeLog
 */
public enum MqConsumeStatus {
    /**
     * 处理中：消费者已获取消息锁并开始执行业务逻辑（通过 {@code locked_until} 租约控制并发）
     */
    PROCESSING,

    /**
     * 成功：消息已成功处理并完成业务落库，后续相同 messageId 的请求将被幂等拦截
     */
    SUCCEEDED,

    /**
     * 失败：消息处理异常且已达重试上限，进入死信队列或触发告警
     */
    FAILED,

    /**
     * 跳过：消息因幂等校验（已处理过）或前置条件不满足而被安全跳过，不重复执行业务逻辑
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
