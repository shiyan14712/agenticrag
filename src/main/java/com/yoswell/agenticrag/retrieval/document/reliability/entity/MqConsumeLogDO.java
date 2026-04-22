package com.yoswell.agenticrag.retrieval.document.reliability.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;


/**
 * Persistence object for message consumption idempotency and audit log.
 *
 * <p>The table records each consume attempt with a business identity key so
 * repeated deliveries can be safely deduplicated and traced.</p>
 */
@Data
@TableName("mq_consume_log")
public class MqConsumeLogDO {

    /** Auto-increment primary key. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Consumer group handling the message. */
    @TableField("consumer_group")
    private String consumerGroup;

    /** Kafka topic from which message is consumed. */
    @TableField("topic")
    private String topic;

    /** Business idempotency identity key for a consumed message. */
    @TableField("message_identity")
    private String messageIdentity;

    /** Kafka message key. */
    @TableField("message_key")
    private String messageKey;

    /** Hash of payload content used as deduplication fallback evidence. */
    @TableField("payload_hash")
    private String payloadHash;

    /** Linked async task identifier, if consumption is task-driven. */
    @TableField("task_id")
    private String taskId;

    /** Related business document identifier, if available. */
    @TableField("document_id")
    private String documentId;

    /** Consume state, for example PROCESSING, SUCCEEDED, FAILED, SKIPPED. */
    @TableField("status")
    private String status;

    /** Total number of consume attempts for this identity. */
    @TableField("consume_count")
    private Integer consumeCount;

    /** Processing lease expiration time to prevent concurrent duplicate handling. */
    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    /** Time when current processing attempt started. */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** Time when current processing attempt finished. */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** Latest failure summary for this consume identity. */
    @TableField("last_error")
    private String lastError;

    /** Row creation timestamp managed by database default. */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** Row update timestamp managed by database on update trigger. */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
