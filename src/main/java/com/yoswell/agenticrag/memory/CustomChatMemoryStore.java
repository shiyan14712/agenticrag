package com.yoswell.agenticrag.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.entity.UserGlobalMemory;
import com.yoswell.agenticrag.repository.UserGlobalMemoryRepository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

@Component
public class CustomChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(CustomChatMemoryStore.class);
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserGlobalMemoryRepository userGlobalMemoryRepo;
    
    // Config: L1 = 5 turns (approx 10 msg), L2 = 6-15, L3 = 15+
    private static final int L1_LIMIT = 10;
    private static final int L2_LIMIT = 30;

    public CustomChatMemoryStore(RedisTemplate<String, Object> redisTemplate, UserGlobalMemoryRepository userGlobalMemoryRepo) {
        this.redisTemplate = redisTemplate;
        this.userGlobalMemoryRepo = userGlobalMemoryRepo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages(Object memoryId) {
        log.info("Retrieving memory for session: {}", memoryId);
        String redisKey = "session:memory:l1:" + memoryId;
        
        List<ChatMessage> l1Messages = (List<ChatMessage>) redisTemplate.opsForValue().get(redisKey);
        
        if (l1Messages == null || l1Messages.isEmpty()) {
            l1Messages = new ArrayList<>();
            // Session is new or expired. Load Global Memory from MySQL as interceptor.
            // Assuming memoryId pattern is "userId_sessionId", else we just pass userId directly if memoryId = userId
            String userId = memoryId.toString().split("_")[0];
            
            List<UserGlobalMemory> preferences = userGlobalMemoryRepo.findByUserId(userId);
            if (!preferences.isEmpty()) {
                String globalMemStr = "Here are long-term facts/preferences you must remember about this user:\n" +
                    preferences.stream()
                        .map(p -> "- " + p.getPreferenceKey() + ": " + p.getPreferenceValue())
                        .collect(Collectors.joining("\n"));
                        
                log.info("Injecting Global Memory into new session context: {}", globalMemStr);
                l1Messages.add(SystemMessage.from(globalMemStr));
            }
        }
        
        return l1Messages; 
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        log.info("Updating memory for session: {}. Messages count: {}", memoryId, messages.size());
        
        if (messages.size() > L1_LIMIT) {
            log.info("L1 size exceeded, scheduling async L2 Summarization thread for session: {}", memoryId);
            // Slice the newest L1 limits to keep in fast memory
            List<ChatMessage> recentMessages = new ArrayList<>(messages.subList(messages.size() - L1_LIMIT, messages.size()));
            redisTemplate.opsForValue().set("session:memory:l1:" + memoryId, recentMessages, 12, TimeUnit.HOURS);
            
            if (messages.size() > L2_LIMIT) {
                log.info("L2 size exceeded, scheduling async L3 Entity Extraction for session: {}", memoryId);
            }
        } else {
            // Save raw to Redis (L1)
            redisTemplate.opsForValue().set("session:memory:l1:" + memoryId, messages, 12, TimeUnit.HOURS);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        log.info("Deleting memory for session: {}", memoryId);
        redisTemplate.delete("session:memory:l1:" + memoryId);
        redisTemplate.delete("session:memory:l2:" + memoryId);
        redisTemplate.delete("session:memory:l3:" + memoryId);
    }
}
