package com.yoswell.agenticrag.retrieval.document.reliability.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;


/**
 * Persistence object for the document async task ledger.
 *
 * <p>The table tracks document-related asynchronous actions (parse, vectorize,
 * delete) and their lifecycle so business state is visible independently from
 * MQ delivery state.</p>
 */
@Data
@TableName("document_async_task")
public class DocumentAsyncTaskDO {

    /** Auto-increment primary key. */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Business task identifier, globally unique in the task ledger. */
    @TableField("task_id")
    private String taskId;

    /** Related business document identifier. */
    @TableField("document_id")
    private String documentId;

    /** Tenant ownership boundary for zero-trust isolation. */
    @TableField("tenant_id")
    private String tenantId;

    /** Task category, for example DOCUMENT_PARSE, DOCUMENT_VECTORIZATION, DOCUMENT_DELETE. */
    @TableField("task_type")
    private String taskType;

    /** Task state, for example PENDING, DISPATCHED, RUNNING, SUCCEEDED, FAILED, SKIPPED. */
    @TableField("status")
    private String status;

    /** Kafka topic associated with this task, if dispatched through MQ. */
    @TableField("topic")
    private String topic;

    /** Kafka message key used for partition routing and ordering. */
    @TableField("message_key")
    private String messageKey;

    /** Linked transactional outbox identifier when task is emitted through outbox. */
    @TableField("outbox_id")
    private String outboxId;

    /** Number of processing attempts performed for this task. */
    @TableField("attempt_count")
    private Integer attemptCount;

    /** Last observed message identifier from producer or consumer side. */
    @TableField("last_message_id")
    private String lastMessageId;

    /** Latest failure summary for troubleshooting and monitoring. */
    @TableField("last_error")
    private String lastError;

    /** Time when task processing started. */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** Time when task reached a terminal state. */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** Row creation timestamp managed by database default. */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** Row update timestamp managed by database on update trigger. */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
