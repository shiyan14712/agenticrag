package com.yoswell.agenticrag.platform.session.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.memory.constants.MemoryStoreConstants;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatMessageDO;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Chat message persistence service.
 */
@Service
@Slf4j
public class ChatMessageService {

    public static final String CONTENT_TYPE_TEXT = "text";
    public static final String CONTENT_TYPE_TOOL_CALL = "tool_call";
    public static final String CONTENT_TYPE_TOOL_RESULT = "tool_result";

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
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setContentType(CONTENT_TYPE_TEXT);
        msg.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1);
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
    }

    @Transactional
    public void saveAssistantMessage(String sessionId, String content, Object metadata) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent(content);
        msg.setContentType(CONTENT_TYPE_TEXT);
        msg.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1);
        msg.setMetadata(serializeMetadata(sessionId, metadata));
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
    }

    /**
     * Persist an assistant tool-call turn so chat history can replay the agent loop.
     */
    @Transactional
    public void saveAssistantToolCallMessage(String sessionId, ToolExecutionRequest toolRequest, int turn) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        String arguments = toolRequest.arguments() == null ? "" : toolRequest.arguments();
        msg.setContent(arguments);
        msg.setContentType(CONTENT_TYPE_TOOL_CALL);
        msg.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1);
        msg.setMetadata(serializeMetadata(sessionId, new ToolCallMetadata(
                turn,
                toolRequest.id(),
                toolRequest.name(),
                arguments)));
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
    }

    /**
     * Persist a tool observation so chat history can replay the agent loop.
     */
    @Transactional
    public void saveToolResultMessage(String sessionId,
                                      ToolExecutionRequest toolRequest,
                                      ToolExecutionResult result,
                                      boolean failed,
                                      long durationMs,
                                      int turn) {
        ChatMessageDO msg = new ChatMessageDO();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(sessionId);
        msg.setRole("tool");
        msg.setContent(result.resultText() == null ? "" : result.resultText());
        msg.setContentType(CONTENT_TYPE_TOOL_RESULT);
        msg.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1);
        msg.setMetadata(serializeMetadata(sessionId, new ToolResultMetadata(
                turn,
                toolRequest.id(),
                toolRequest.name(),
                failed,
                durationMs)));
        messageMapper.insert(msg);
        incrementSessionMessageCount(sessionId, 1);
    }

    private String serializeMetadata(String sessionId, Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException e) {
            log.error("[ChatMessageService] Failed to serialize message metadata: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "消息元数据序列化失败，请稍后重试");
        }
    }

    private void incrementSessionMessageCount(String sessionId, int delta) {
        ChatSessionDO session = sessionMapper.selectOne(
                new QueryWrapper<ChatSessionDO>().eq("session_id", sessionId));
        if (session == null) {
            throw new BusinessException(
                    ErrorCode.SESSION_NOT_FOUND.getCode(),
                    ErrorCode.SESSION_NOT_FOUND.getMessage() + sessionId);
        }

        Integer messageCount = session.getMessageCount();
        int currentCount = messageCount == null ? 0 : messageCount;
        session.setMessageCount(currentCount + delta);
        sessionMapper.updateById(session);
        redisManager.cacheSessionMeta(session);
    }

    private record ToolCallMetadata(int turn, String toolCallId, String toolName, String arguments) {
    }

    private record ToolResultMetadata(
            int turn,
            String toolCallId,
            String toolName,
            boolean failed,
            long durationMs) {
    }
}
