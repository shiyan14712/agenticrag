package com.yoswell.agenticrag.retrieval.document.reliability.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yoswell.agenticrag.retrieval.document.model.DocumentKafkaTopic;
import com.yoswell.agenticrag.retrieval.document.reliability.model.MessageOutboxEventType;
import com.yoswell.agenticrag.retrieval.document.reliability.model.MessageOutboxStatus;

import lombok.Data;


/**
 * Persistence object for transactional message outbox.
 *
 * <p>Rows are created in the same business transaction as metadata changes.
 * A background dispatcher scans pending rows and publishes them to Kafka with
 * retry and backoff control.</p>
 */
@Data
@TableName("mq_outbox")
public class MessageOutboxDO {

    /** Auto-increment primary key. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Business outbox identifier, unique for idempotent tracking. */
    @TableField("outbox_id")
    private String outboxId;

    /** Aggregate type owning this event, for example DOCUMENT. */
    @TableField("aggregate_type")
    private String aggregateType;

    /** Aggregate business identifier, for example document_id. */
    @TableField("aggregate_id")
    private String aggregateId;

    /** Linked document async task identifier, if available. */
    @TableField("task_id")
    private String taskId;

    /** Domain event type used by consumers. */
    @TableField("event_type")
    private MessageOutboxEventType eventType;

    /** Destination Kafka topic. */
    @TableField("topic")
    private DocumentKafkaTopic topic;

    /** Kafka message key for ordering and partition affinity. */
    @TableField("message_key")
    private String messageKey;

    /** Serialized message payload body. */
    @TableField("payload")
    private String payload;

    /** Dispatch status, for example PENDING, DISPATCHING, SENT, FAILED. */
    @TableField("status")
    private MessageOutboxStatus status;

    /** Number of failed dispatch retries. */
    @TableField("retry_count")
    private Integer retryCount;

    /** Earliest time this row is allowed to be retried by dispatcher. */
    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    /** Timestamp when message was successfully sent. */
    @TableField("sent_at")
    private LocalDateTime sentAt;

    /** Latest dispatch error summary for observability. */
    @TableField("last_error")
    private String lastError;

    /** Row creation timestamp managed by database default. */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** Row update timestamp managed by database on update trigger. */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
