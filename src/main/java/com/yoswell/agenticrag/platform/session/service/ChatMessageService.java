package com.yoswell.agenticrag.platform.session.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatMessageDO;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 聊天消息服务
 *
 * <p>负责用户消息和助手消息的落库，并维护会话消息计数及缓存同步</p>
 */
@Service
@Slf4j
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

    /**
     * 保存用户消息
     *
     * @param sessionId 会话 ID
     * @param content 用户消息内容
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
    }

    /**
     * 保存助手消息
     *
     * @param sessionId 会话 ID
     * @param content 助手消息内容
     * @param metadata 可选元数据（将序列化为 JSON 保存）
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String content, Object metadata) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent(content);

        if (metadata != null) {
            try {
                msg.setMetadata(objectMapper.writeValueAsString(metadata));
            } catch (JacksonException e) {
                log.error("[ChatMessageService] 助手消息元数据序列化失败: sessionId={}", sessionId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "消息元数据序列化失败，请稍后重试");
            }
        }
        messageMapper.insert(msg);

        incrementSessionMessageCount(sessionId, 1);
    }

    private void incrementSessionMessageCount(String sessionId, int delta) {
        ChatSessionDO session = sessionMapper.selectOne(
                new QueryWrapper<ChatSessionDO>().eq("session_id", sessionId)
        );
        if (session == null) {
            throw new BusinessException(
                    ErrorCode.SESSION_NOT_FOUND.getCode(),
                    ErrorCode.SESSION_NOT_FOUND.getMessage() + sessionId
            );
        }

        Integer messageCount = session.getMessageCount();
        int currentCount = messageCount == null ? 0 : messageCount;
        session.setMessageCount(currentCount + delta);
        sessionMapper.updateById(session);
        // TODO(session-messages-cache): 消息写入后应失效/刷新 session:messages:* 缓存，避免历史分页数据脏读。
        // TODO(session-messages-cache): 建议新增 redisManager.invalidateSessionMessagesCache(sessionId)。
        redisManager.cacheSessionMeta(session);
    }
}
