package com.yoswell.agenticrag.core.memory.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户全局记忆实体类
 * 对应数据库表：user_global_memory
 * 
 * 用户跨会话级别长期记忆表：由大模型在发现用户偏好时存入，或拦截器拉取作为 System Prompt
 * 用于存储用户的长期偏好和习惯，实现跨会话的个性化服务
 * 
 * @author AgenticRAG
 * @since 2026-03-28
 */
@Data
@TableName("user_global_memory")
public class UserGlobalMemory {

    /**
     * 主键 ID
     * 自增主键，数据库内部唯一标识
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID
     * 用户 ID 或租户下的唯一标识，用于关联特定用户
     */
    @TableField("user_id")
    private String userId;

    /**
     * 偏好键名/主题
     * 偏好或记忆的键名/主题，用于分类和检索特定的用户偏好
     */
    @TableField("preference_key")
    private String preferenceKey;

    /**
     * 偏好值/详细记录
     * 具体记录的长期偏好细节，包含用户的实际偏好内容
     */
    @TableField("preference_value")
    private String preferenceValue;

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
}
