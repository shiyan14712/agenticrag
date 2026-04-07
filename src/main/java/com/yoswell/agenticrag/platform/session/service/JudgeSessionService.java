package com.yoswell.agenticrag.platform.session.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeSessionService {

    private final ChatSessionMapper chatSessionMapper;

    public boolean isSessionExists(String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        Long count = chatSessionMapper.selectCount(
                new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
        );

        return count != null && count > 0;
    }

    public void validateSessionExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SESSION_ID.getCode(), ErrorCode.INVALID_SESSION_ID.getMessage());
        }

        if (!isSessionExists(sessionId)) {
            log.warn("[JudgeSessionService] 会话不存在: sessionId={}", sessionId);
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND.getCode(),
                    ErrorCode.SESSION_NOT_FOUND.getMessage() + sessionId);
        }
    }
}
