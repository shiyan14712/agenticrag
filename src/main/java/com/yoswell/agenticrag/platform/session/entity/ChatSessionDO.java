package com.yoswell.agenticrag.platform.session.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 聊天会话实体类
 * 对应数据库表：chat_session
 * 
 * 用于存储用户聊天会话的基本信息、状态和管理数据
 * 支持会话标题自动生成、消息计数、压缩管理等功能
 * 
 * @author AgenticRAG
 * @since 2026-03-28
 */
@Data
@TableName("chat_session")
public class ChatSessionDO {

    /**
     * 主键 ID
     * 自增主键，数据库内部唯一标识
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     * UUID v7 格式，兼顾唯一性与时间排序，对外暴露的会话标识
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 用户 ID
     * 关联用户表，标识会话所属的用户
     */
    @TableField("user_id")
    private String userId;

    /**
     * 会话标题
     * 首轮对话后由 LLM 自动生成，描述会话主题内容
     */
    @TableField("title")
    private String title;

     /**
      * 会话状态
      * 状态值：0=ACTIVE（活跃）/ 1=ARCHIVED（已归档）/ 2=DELETED（已删除）
      */
    @TableField("status")
    private Integer status;

    /**
     * 模型 ID
     * 该会话绑定的模型标识（可选），用于指定特定 AI 模型
     */
    @TableField("model_id")
    private String modelId;

    /**
     * 消息计数器
     * 记录会话中的消息数量，用于触发 L2/L3 压缩策略
     */
    @TableField("message_count")
    private Integer messageCount;

    /**
     * 会话摘要
     * 会话级摘要内容，L3 压缩后的最终产物
     */
    @TableField("summary")
    private String summary;

    /**
     * 是否置顶
     * 标识会话是否被用户置顶，便于快速访问
     */
    @TableField("pinned")
    private Boolean pinned;

    /**
     * 创建时间
     * 记录创建时间，由数据库自动维护，插入和更新时不修改此字段
     */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     * 记录最后更新时间，由数据库自动维护，插入和更新时不修改此字段
     */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    /**
     * 归档时间
     * 记录会话被归档的时间点
     */
    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
