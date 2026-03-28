package com.yoswell.agenticrag.service.session;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.cache.SessionRedisManager;
import com.yoswell.agenticrag.entity.ChatMessage;
import com.yoswell.agenticrag.entity.ChatSession;
import com.yoswell.agenticrag.repository.ChatMessageRepository;
import com.yoswell.agenticrag.repository.ChatSessionRepository;

@Service
public class ChatMessageService {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;
    private final SessionRedisManager redisManager;
    private final ObjectMapper objectMapper;
    private final SessionTitleGenerator titleGenerator;

    public ChatMessageService(ChatMessageRepository messageRepository,
                              ChatSessionRepository sessionRepository,
                              SessionRedisManager redisManager,
                              ObjectMapper objectMapper,
                              SessionTitleGenerator titleGenerator) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.redisManager = redisManager;
        this.objectMapper = objectMapper;
        this.titleGenerator = titleGenerator;
    }

    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        messageRepository.insert(msg);

        // Update cache implicitly or using SessionRedisManager
    }

    @Transactional
    public void saveAssistantMessage(String sessionId, String content, Object metadata) {
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent(content);

        if (metadata != null) {
            try {
                msg.setMetadata(objectMapper.writeValueAsString(metadata));
            } catch (JsonProcessingException e) {
            }
        }
        messageRepository.insert(msg);

        // Update count
        ChatSession session = sessionRepository.selectOne(
            new QueryWrapper<ChatSession>().eq("session_id", sessionId)
        );
        if (session != null) {
            session.setMessageCount(session.getMessageCount() + 2);
            sessionRepository.updateById(session);
            redisManager.cacheSessionMeta(session);

            // Generate title on first reply
            if (session.getTitle() == null && session.getMessageCount() >= 2) {
                titleGenerator.generateTitleAsync(sessionId, "user query", content);
            }
        }
    }
}