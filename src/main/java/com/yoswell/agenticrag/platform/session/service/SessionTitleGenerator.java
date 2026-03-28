package com.yoswell.agenticrag.platform.session.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.platform.session.cache.SessionRedisManager;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import dev.langchain4j.model.chat.ChatLanguageModel;

@Service
public class SessionTitleGenerator {

    private final ChatSessionMapper sessionMapper;
    private final SessionRedisManager redisManager;
    private final ChatLanguageModel chatLanguageModel;

    public SessionTitleGenerator(ChatSessionMapper sessionMapper,
                                 SessionRedisManager redisManager,
                                 ChatLanguageModel chatLanguageModel) {
        this.sessionMapper = sessionMapper;
        this.redisManager = redisManager;
        this.chatLanguageModel = chatLanguageModel;
    }

    @Async
    @Transactional
    public void generateTitleAsync(String sessionId, String userMessage, String assistantMessage) {
        ChatSession session = sessionMapper.selectOne(
            new QueryWrapper<ChatSession>().eq("session_id", sessionId)
        );
        if (session == null || session.getTitle() != null) {
            return;
        }

        try {
            String trimmedUser = userMessage.length() > 500 ? userMessage.substring(0, 500) : userMessage;
            String trimmedAssistant = assistantMessage.length() > 500 ? assistantMessage.substring(0, 500) : assistantMessage;

            String prompt = String.format(
                "请根据以下对话内容，生成一个简洁的中文标题（不超过 20 个字，不要引号，直接输出标题文本）：\n用户：%s\n助手：%s",
                trimmedUser, trimmedAssistant
            );

            String generatedTitle = chatLanguageModel.generate(prompt).replaceAll("\"", "").trim();

            if (generatedTitle.length() > 200) {
                generatedTitle = generatedTitle.substring(0, 200);
            }

            session.setTitle(generatedTitle);
            sessionMapper.updateById(session);
            redisManager.cacheSessionMeta(session);

        } catch (Exception e) {
        }
    }
}
