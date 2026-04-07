package com.yoswell.agenticrag.core.memory.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yoswell.agenticrag.core.memory.dto.UserGlobalMemoryDTO;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;

public interface UserGlobalMemoryService extends IService<UserGlobalMemory> {

    List<UserGlobalMemoryDTO> listByUserId(String userId);

}
