package com.yoswell.agenticrag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionRepository extends BaseMapper<ChatSession> {
}
