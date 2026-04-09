package com.yoswell.agenticrag.core.agent.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.ai.SimpleChatAgent;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Simple LLM-backed chat/title service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SimpleChatAgent simpleChatAgent;
    private final ChatSessionMapper chatSessionMapper;

    public String generateTitle(String message) {
        log.info("[ChatService Title Generation] Start generating title");
        String title = simpleChatAgent.generateTitle(message);

        if (title != null) {
            title = title.replaceAll("[\"\'\n\r]", "").trim();
            if (title.length() > 200) {
                title = title.substring(0, 195) + "...";
            }
        }

        log.info("[ChatService Title Generation] Title generation finished: title={}", title);
        return title;
    }

    public String getSessionTitle(String sessionId, String userId) {
        LambdaQueryWrapper<ChatSessionDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatSessionDO::getSessionId, sessionId)
                .eq(ChatSessionDO::getUserId, userId)
                .select(ChatSessionDO::getTitle);
        ChatSessionDO session = chatSessionMapper.selectOne(queryWrapper);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return session.getTitle();
    }

    public void generateTitleAndSave(String sessionId, String firstUserQueryContent) {
        log.info("[ChatService Title Generation] Start generating and saving title for session {}", sessionId);

        String title = generateTitle(firstUserQueryContent);
        if (title == null || title.isBlank()) {
            log.warn("[ChatService Title Generation] Generated title is blank for session {}", sessionId);
            throw new IllegalStateException("Generated title is blank");
        }

        ChatSessionDO sessionUpdate = new ChatSessionDO();
        sessionUpdate.setTitle(title);

        UpdateWrapper<ChatSessionDO> wrapper = new UpdateWrapper<>();
        wrapper.eq("session_id", sessionId)
                .isNull("title");

        int updatedRows = chatSessionMapper.update(sessionUpdate, wrapper);
        if (updatedRows <= 0) {
            log.warn("[ChatService Title Generation] Title was not updated because session was missing or title already existed: sessionId={}",
                    sessionId);
            throw new IllegalStateException("Title update skipped");
        }

        log.info("[ChatService Title Generation] Successfully generated and saved title for session {}: 标题-{}",
                sessionId, title);
    }
}
