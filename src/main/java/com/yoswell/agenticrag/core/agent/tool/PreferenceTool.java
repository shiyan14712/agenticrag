package com.yoswell.agenticrag.core.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;

import dev.langchain4j.agent.tool.Tool;

@Component
public class PreferenceTool {

    private static final Logger log = LoggerFactory.getLogger(PreferenceTool.class);
    private final UserGlobalMemoryMapper userGlobalMemoryMapper;

    public PreferenceTool(UserGlobalMemoryMapper userGlobalMemoryMapper) {
        this.userGlobalMemoryMapper = userGlobalMemoryMapper;
    }

    @Tool("save_user_preference")
    public String saveUserPreference(String userId, String preferenceKey, String preferenceValue) {
        log.info("Saving user memory / preference to MySQL: [{}={} for user {}]", preferenceKey, preferenceValue, userId);
        
        UserGlobalMemory memory = new UserGlobalMemory();
        memory.setUserId(userId);
        memory.setPreferenceKey(preferenceKey);
        memory.setPreferenceValue(preferenceValue);
        
        userGlobalMemoryMapper.insert(memory);
        return "Preference saved successfully.";
    }
}
