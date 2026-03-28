package com.yoswell.agenticrag.platform.session.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体类
 * 对应数据库表：chat_message
 * 
 * 用于存储会话中的聊天消息内容、元数据和压缩状态
 * 支持多种角色（user/assistant/system/tool）和消息类型，以及上下文压缩管理
 * 
 * @author AgenticRAG
 * @since 2026-03-28
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /**
     * 主键 ID
     * 自增主键，数据库内部唯一标识
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息 ID
     * UUID 格式，对外暴露的唯一消息标识
     */
    @TableField("message_id")
    private String messageId;

    /**
     * 会话 ID
     * 关联 chat_session.session_id，标识消息所属的会话
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 角色类型
     * 消息发送者角色：user（用户）/ assistant（助手）/ system（系统）/ tool（工具）
     */
    @TableField("role")
    private String role;

    /**
     * 消息内容
     * 消息正文，支持 Markdown 或 JSON 格式
     */
    @TableField("content")
    private String content;

    /**
     * 内容类型
     * 消息内容类型：text（文本）/ tool_call（工具调用）/ tool_result（工具结果）
     */
    @TableField("content_type")
    private String contentType;

    /**
     * Token 计数
     * 该条消息估算的 token 数量，用于上下文窗口管理
     */
    @TableField("token_count")
    private Integer tokenCount;

    /**
     * 结构化元数据
     * JSON 格式扩展字段，可包含 citations[]（引用列表）、tool_name（工具名称）等信息
     */
    @TableField("metadata")
    private String metadata;

    /**
     * 压缩级别
     * 压缩状态标记：L1（原文）/ L2（摘要）/ L3（实体）
     */
    @TableField("compression_level")
    private String compressionLevel;

    /**
     * 压缩后的内容
     * 压缩后的摘要文本，在 L2/L3 级别时填充
     */
    @TableField("compressed_content")
    private String compressedContent;

    /**
     * 创建时间
     * 记录创建时间，由数据库自动维护，插入和更新时不修改此字段
     */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
