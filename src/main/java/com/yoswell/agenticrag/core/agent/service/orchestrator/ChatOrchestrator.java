package com.yoswell.agenticrag.core.agent.service.orchestrator;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.common.constants.ChatCacheConstants;
import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.service.ChatService;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;

import dev.langchain4j.service.TokenStream;
import tools.jackson.databind.ObjectMapper;

@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final EnterpriseAgent enterpriseAgent;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;
    private final ChatService chatService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatSessionMapper chatSessionMapper;

    public ChatOrchestrator(EnterpriseAgent enterpriseAgent,
                            ObjectMapper objectMapper,
                            ChatMessageService chatMessageService,
                            RagRetrievalContextHolder ragRetrievalContextHolder,
                            ChatService chatService,
                            StringRedisTemplate stringRedisTemplate,
                            ChatSessionMapper chatSessionMapper) {
        this.enterpriseAgent = enterpriseAgent;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
        this.chatService = chatService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.chatSessionMapper = chatSessionMapper;
    }

    public SseEmitter dispatchDynamicStream(String sessionId, String message) {
        SseEmitter emitter = new SseEmitter(10L * 60 * 1000); // 10 minutes timeout
        
        Thread.startVirtualThread(() -> {
            AutoCloseable retrievalScope = null;
            try {
                chatMessageService.saveUserMessage(sessionId, message);

                String titleGenKey = ChatCacheConstants.SESSION_TITLE_GEN_PREFIX + sessionId;
                Boolean isFirstMessage = stringRedisTemplate.opsForValue().setIfAbsent(titleGenKey, "1", Duration.ofHours(24));
                
                if (Boolean.TRUE.equals(isFirstMessage)) {
                    ChatSession session = chatSessionMapper.selectOne(new QueryWrapper<ChatSession>().eq("session_id", sessionId).select("session_id", "title"));
                    if (session != null && session.getTitle() == null) {
                        Thread.startVirtualThread(() -> {
                            log.info("[Chat Orchestrator] Triggering async title generation for session: {}", sessionId);
                            try {
                                chatService.generateTitleAndSave(sessionId, message);
                            } catch (Exception e) {
                                log.error("[Chat Orchestrator] Failed to generate title, removing redis key to allow retry next time", e);
                                stringRedisTemplate.delete(titleGenKey);
                            }
                        });
                    }
                }

                StringBuilder fullResponse = new StringBuilder();
                retrievalScope = ragRetrievalContextHolder.bindSession(sessionId);
                final AutoCloseable finalRetrievalScope = retrievalScope;
                
                log.info("[Chat Orchestrator] LLM React process and reasoning started for session: {}", sessionId);
                TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                tokenStream
                    .onNext(token -> {
                        fullResponse.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("message").data(token));
                        } catch (Exception ex) {
                            log.error("[Chat Orchestrator] Failed to send token", ex);
                        }
                    })
                    .onComplete(response -> {
                        try {
                            List<CitationDTO> citations = ragRetrievalContextHolder.consume(sessionId)
                                    .map(RagSearchResultDTO::citations)
                                    .orElse(List.of());
                            emitCitationsWidget(emitter, citations);
                            
                            chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                            emitter.complete();
                            log.info("[Chat Orchestrator] Agent process completed successfully for session: {}", sessionId);
                        } finally {
                            closeQuietly(finalRetrievalScope);
                        }
                    })
                    .onError(error -> {
                        try {
                            log.error("[Chat Orchestrator] Token stream encountered an error", error);
                            emitter.completeWithError(error);
                        } finally {
                            closeQuietly(finalRetrievalScope);
                        }
                    })
                    .start();
            } catch (Exception e) {
                log.error("[Chat Orchestrator] Error inside WebMVC Virtual Thread execution", e);
                emitter.completeWithError(e);
                closeQuietly(retrievalScope);
            }
        });
        
        return emitter;
    }

    private void emitCitationsWidget(SseEmitter emitter, List<CitationDTO> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        try {
            String citationsJson = objectMapper.writeValueAsString(citations);
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
        } catch (Exception e) {
            log.error("[Chat Orchestrator] Error occurred while emitting citations widget", e);
        }
    }

    private void closeQuietly(AutoCloseable scope) {
        if (scope == null) return;
        try { scope.close(); } catch (Exception e) { log.debug("Failed to close rag retrieval scope cleanly", e); }
    }
}
