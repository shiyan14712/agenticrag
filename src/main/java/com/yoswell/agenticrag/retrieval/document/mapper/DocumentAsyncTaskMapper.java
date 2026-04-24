package com.yoswell.agenticrag.retrieval.document.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.DocumentAsyncTaskDO;

@Mapper
public interface DocumentAsyncTaskMapper extends BaseMapper<DocumentAsyncTaskDO> {
}
