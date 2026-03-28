package com.yoswell.agenticrag.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.entity.UserGlobalMemory;
import com.yoswell.agenticrag.repository.UserGlobalMemoryRepository;

import dev.langchain4j.agent.tool.Tool;

@Component
public class PreferenceTool {

    private static final Logger log = LoggerFactory.getLogger(PreferenceTool.class);
    private final UserGlobalMemoryRepository userGlobalMemoryRepo;

    public PreferenceTool(UserGlobalMemoryRepository userGlobalMemoryRepo) {
        this.userGlobalMemoryRepo = userGlobalMemoryRepo;
    }

    @Tool("save_user_preference")
    public String saveUserPreference(String userId, String preferenceKey, String preferenceValue) {
        log.info("Saving user memory / preference to MySQL: [{}={} for user {}]", preferenceKey, preferenceValue, userId);
        
        UserGlobalMemory memory = new UserGlobalMemory();
        memory.setUserId(userId);
        memory.setPreferenceKey(preferenceKey);
        memory.setPreferenceValue(preferenceValue);
        
        userGlobalMemoryRepo.insert(memory);
        return "Preference saved successfully.";
    }
}
