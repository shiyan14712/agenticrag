package com.yoswell.agenticrag.retrieval.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档元数据实体类
 * 对应数据库表：document_metadata
 * 
 * 文档元数据架构指针表：仅存储状态、索引依据与 MinIO URL，绝不存储内容
 * 用于企业文档元数据流水线跟踪，支持多租户隔离和知识库管理
 * 
 * @author AgenticRAG
 * @since 2026-03-28
 */
@Data
@TableName("document_metadata")
public class DocumentMetadata {

    /**
     * 主键 ID
     * 自增主键，数据库内部唯一标识
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 多租户 ID
     * 用于多租户隔离，确保不同租户的数据独立性
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 知识库 ID
     * 文档所属的逻辑知识库标识，用于分类和管理文档
     */
    @TableField("kb_id")
    private String kbId;

    /**
     * 原始文件名
     * 用户上传文件时的原始文件名称
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 业务文档 ID
     * 对外暴露的唯一追踪编号，UUID 格式，用于业务层面的文档识别和追踪
     */
    @TableField("document_id")
    private String documentId;

    /**
     * MinIO 存储 URL
     * 指向 MinIO 对象存储的物理存储地址，用于获取文件实际内容
     */
    @TableField("minio_url")
    private String minioUrl;

    /**
     * 文件扩展名
     * 决定解析策略工厂的处理方式（如：md, pdf, txt 等）
     */
    @TableField("file_extension")
    private String fileExtension;

    /**
     * 文档处理状态
     * 状态流转：UPLOADED（已上传） -> PARSING（解析中） -> VECTORIZED（已向量化） -> FAILED（失败）
     */
    @TableField("status")
    private String status;

    /**
     * 允许访问的角色列表
     * 权限控制字段，逗号分隔的角色列表，存入 ES 用作检索时的拦截过滤
     */
    @TableField("allowed_roles")
    private String allowedRoles;

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
