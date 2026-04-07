package com.yoswell.agenticrag.retrieval.document.reliability.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.MessageOutboxDO;

@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutboxDO> {
}
