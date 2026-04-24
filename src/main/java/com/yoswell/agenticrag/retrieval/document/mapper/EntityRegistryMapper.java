package com.yoswell.agenticrag.retrieval.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.retrieval.document.enrichment.entity.EntityRegistryDO;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EntityRegistryMapper extends BaseMapper<EntityRegistryDO> {
}
