package com.yoswell.agenticrag.retrieval.document.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;

@Mapper
public interface DocumentMetadataMapper extends BaseMapper<DocumentMetadata> {
}
