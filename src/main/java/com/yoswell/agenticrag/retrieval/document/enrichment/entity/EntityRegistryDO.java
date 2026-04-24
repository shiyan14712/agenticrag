package com.yoswell.agenticrag.retrieval.document.enrichment.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("entity_registry")
public class EntityRegistryDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private String documentId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("mention")
    private String mention;

    @TableField("full_name")
    private String fullName;

    @TableField("definition")
    private String definition;

    @TableField("category")
    private String category;

    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
