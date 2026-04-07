package com.yoswell.agenticrag.core.memory.service.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yoswell.agenticrag.core.memory.dto.UserGlobalMemoryDTO;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.core.memory.service.UserGlobalMemoryService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UserGlobalMemoryServiceImpl extends ServiceImpl<UserGlobalMemoryMapper, UserGlobalMemory> implements UserGlobalMemoryService {

    @Override
    public List<UserGlobalMemoryDTO> listByUserId(String userId) {
        log.info("[UserGlobalMemoryService] 查询用户全局记忆: userId={}", userId);
        List<UserGlobalMemory> records = this.list(new QueryWrapper<UserGlobalMemory>().eq("user_id", userId));
        List<UserGlobalMemoryDTO> memories = records.stream().map(this::convertToDTO).collect(Collectors.toList());
        log.info("[UserGlobalMemoryService] 用户全局记忆查询完成: userId={}, memoryCount={}", userId, memories.size());
        return memories;
    }

    private UserGlobalMemoryDTO convertToDTO(UserGlobalMemory entity) {
        UserGlobalMemoryDTO dto = new UserGlobalMemoryDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setPreferenceKey(entity.getPreferenceKey());
        dto.setPreferenceValue(entity.getPreferenceValue());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
