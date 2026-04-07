package com.yoswell.agenticrag.retrieval.document.reliability.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("mq_consume_log")
public class MqConsumeLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("consumer_group")
    private String consumerGroup;

    @TableField("topic")
    private String topic;

    @TableField("message_identity")
    private String messageIdentity;

    @TableField("message_key")
    private String messageKey;

    @TableField("payload_hash")
    private String payloadHash;

    @TableField("task_id")
    private String taskId;

    @TableField("document_id")
    private String documentId;

    @TableField("status")
    private String status;

    @TableField("consume_count")
    private Integer consumeCount;

    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("last_error")
    private String lastError;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
