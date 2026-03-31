package com.yoswell.agenticrag.platform.session.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatMessage;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChatMessageService {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SessionRedisManager redisManager;
    private final ObjectMapper objectMapper;

    public ChatMessageService(ChatMessageMapper messageMapper,
                              ChatSessionMapper sessionMapper,
                              SessionRedisManager redisManager,
                              ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.redisManager = redisManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
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
            } catch (JacksonException e) {
            }
        }
        messageMapper.insert(msg);

        incrementSessionMessageCount(sessionId, 1);
    }

    private ChatSession incrementSessionMessageCount(String sessionId, int delta) {
        ChatSession session = sessionMapper.selectOne(
                new QueryWrapper<ChatSession>().eq("session_id", sessionId)
        );
        if (session == null) {
            return null;
        }

        int currentCount = session.getMessageCount() == null ? 0 : session.getMessageCount();
        session.setMessageCount(currentCount + delta);
        sessionMapper.updateById(session);
        redisManager.cacheSessionMeta(session);
        return session;
    }
}
