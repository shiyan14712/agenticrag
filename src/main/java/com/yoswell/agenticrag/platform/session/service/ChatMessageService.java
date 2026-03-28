package com.yoswell.agenticrag.platform.session.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

@Service
public class ChatMessageService {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SessionRedisManager redisManager;
    private final ObjectMapper objectMapper;
    private final SessionTitleGenerator titleGenerator;

    public ChatMessageService(ChatMessageMapper messageMapper,
                              ChatSessionMapper sessionMapper,
                              SessionRedisManager redisManager,
                              ObjectMapper objectMapper,
                              SessionTitleGenerator titleGenerator) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
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
        messageMapper.insert(msg);
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
        messageMapper.insert(msg);

        // Update count
        ChatSession session = sessionMapper.selectOne(
            new QueryWrapper<ChatSession>().eq("session_id", sessionId)
        );
        if (session != null) {
            session.setMessageCount(session.getMessageCount() + 2);
            sessionMapper.updateById(session);
            redisManager.cacheSessionMeta(session);

            if (session.getTitle() == null && session.getMessageCount() >= 2) {
                titleGenerator.generateTitleAsync(sessionId, "user query", content);
            }
        }
    }
}
