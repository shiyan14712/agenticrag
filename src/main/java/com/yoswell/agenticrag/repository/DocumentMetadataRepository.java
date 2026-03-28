package com.yoswell.agenticrag.repository;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.entity.DocumentMetadata;

@Mapper
public interface DocumentMetadataRepository extends BaseMapper<DocumentMetadata> {
}
