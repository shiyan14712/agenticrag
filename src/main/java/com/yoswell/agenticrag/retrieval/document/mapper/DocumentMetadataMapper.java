package com.yoswell.agenticrag.retrieval.document.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;

@Mapper
/**
 * 文档元数据表的 MyBatis-Plus Mapper。
 *
 * <p>当前直接复用 {@link BaseMapper} 提供的通用 CRUD 能力，
 * 暂时不声明额外 SQL 方法。</p>
 */
public interface DocumentMetadataMapper extends BaseMapper<DocumentMetadata> {
}
