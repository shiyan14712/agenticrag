package com.yoswell.agenticrag.service.session;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yoswell.agenticrag.cache.SessionRedisManager;
import com.yoswell.agenticrag.entity.ChatSession;
import com.yoswell.agenticrag.repository.ChatSessionRepository;

import dev.langchain4j.model.chat.ChatLanguageModel;

@Service
public class SessionTitleGenerator {

    private final ChatSessionRepository sessionRepository;
    private final SessionRedisManager redisManager;
    private final ChatLanguageModel chatLanguageModel;

    public SessionTitleGenerator(ChatSessionRepository sessionRepository,
                                 SessionRedisManager redisManager,
                                 ChatLanguageModel chatLanguageModel) {
        this.sessionRepository = sessionRepository;
        this.redisManager = redisManager;
        this.chatLanguageModel = chatLanguageModel;
    }

    @Async
    @Transactional
    public void generateTitleAsync(String sessionId, String userMessage, String assistantMessage) {
        // Find session
        ChatSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null || session.getTitle() != null) {
            return; // title already generated or session removed
        }

        try {
            // Trim inputs to save tokens
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
            sessionRepository.save(session);
            redisManager.cacheSessionMeta(session);

            // Optional: send SSE event back via Event Publisher or SseService here

        } catch (Exception e) {
            // Handle generation failure gracefully
            e.printStackTrace();
        }
    }
}
