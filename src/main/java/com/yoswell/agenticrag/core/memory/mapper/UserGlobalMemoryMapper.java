package com.yoswell.agenticrag.core.memory.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;

@Mapper
public interface UserGlobalMemoryMapper extends BaseMapper<UserGlobalMemory> {
}
