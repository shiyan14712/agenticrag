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

@Service
public class UserGlobalMemoryServiceImpl extends ServiceImpl<UserGlobalMemoryMapper, UserGlobalMemory> implements UserGlobalMemoryService {

    @Override
    public List<UserGlobalMemoryDTO> listByUserId(String userId) {
        List<UserGlobalMemory> records = this.list(new QueryWrapper<UserGlobalMemory>().eq("user_id", userId));
        return records.stream().map(this::convertToDTO).collect(Collectors.toList());
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
