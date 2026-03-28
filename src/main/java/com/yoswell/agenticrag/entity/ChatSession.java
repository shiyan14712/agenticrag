package com.yoswell.agenticrag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("title")
    private String title;

    @TableField("status")
    private String status;

    @TableField("model_id")
    private String modelId;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("summary")
    private String summary;

    @TableField("pinned")
    private Boolean pinned;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
