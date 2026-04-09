package com.yoswell.agenticrag.retrieval.document.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 文档元数据持久化实体，对应表 {@code document_metadata}
 *
 * <p>这张表只记录文档管理信息和处理状态，不直接保存正文正文位于
 * MinIO，切块和向量数据位于 Elasticsearch</p>
 */
@Data
@TableName("document_metadata")
public class DocumentDO {

    /**
     * 数据库自增主键，仅供内部关联使用
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文档所属租户，用于多租户隔离
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 文档所属知识库
     */
    @TableField("kb_id")
    private String kbId;

    /**
     * 用户上传时看到的原始文件名
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 对外暴露的业务文档 ID，用于查询状态和跨系统追踪
     */
    @TableField("document_id")
    private String documentId;

    /**
     * 文档在 MinIO 中的存储地址
     */
    @TableField("minio_url")
    private String minioUrl;

    /**
     * 文件扩展名，用于选择解析策略
     */
    @TableField("file_extension")
    private String fileExtension;

    /**
     * 当前处理状态，例如 {@code UPLOADED}、{@code PARSING}、
     * {@code VECTORIZED}、{@code FAILED}
     */
    @TableField("status")
    private String status;

    /**
     * 允许访问当前文档的角色列表，使用逗号分隔后持久化
     */
    @TableField("allowed_roles")
    private String allowedRoles;

    /**
     * 创建时间，由数据库维护
     */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /**
     * 最后更新时间，由数据库维护
     */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
