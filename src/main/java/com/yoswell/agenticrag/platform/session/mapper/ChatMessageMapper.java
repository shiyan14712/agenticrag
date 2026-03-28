package com.yoswell.agenticrag.platform.session.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
