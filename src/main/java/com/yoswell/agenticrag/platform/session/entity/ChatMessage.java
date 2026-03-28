package com.yoswell.agenticrag.platform.session.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private String messageId;

    @TableField("session_id")
    private String sessionId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("content_type")
    private String contentType;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("metadata")
    private String metadata;

    @TableField("compression_level")
    private String compressionLevel;

    @TableField("compressed_content")
    private String compressedContent;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
