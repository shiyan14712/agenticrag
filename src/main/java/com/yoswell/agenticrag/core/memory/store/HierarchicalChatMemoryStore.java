package com.yoswell.agenticrag.core.memory.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

@Component
public class HierarchicalChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalChatMemoryStore.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserGlobalMemoryMapper userGlobalMemoryMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatLanguageModel chatLanguageModel;
    private final int maxMessages;
    private final int l1Limit;
    private final int l2Limit;

    public HierarchicalChatMemoryStore(RedisTemplate<String, Object> redisTemplate,
                                       UserGlobalMemoryMapper userGlobalMemoryMapper,
                                       ChatSessionMapper chatSessionMapper,
                                       ChatMessageMapper chatMessageMapper,
                                       ChatLanguageModel chatLanguageModel,
                                       @Value("${rag.memory.max-messages:40}") int maxMessages,
                                       @Value("${rag.memory.l1-limit:10}") int l1Limit,
                                       @Value("${rag.memory.l2-limit:30}") int l2Limit) {
        this.redisTemplate = redisTemplate;
        this.userGlobalMemoryMapper = userGlobalMemoryMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatLanguageModel = chatLanguageModel;
        this.maxMessages = maxMessages;
        this.l1Limit = l1Limit;
        this.l2Limit = l2Limit;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("Retrieving memory for session: {}", sessionId);

        ArrayList<ChatMessage> assembledMessages = new ArrayList<>();
        injectUserPreferences(sessionId, assembledMessages);
        injectSummary(sessionId, "session:memory:l3:", "Long-range session summary", assembledMessages);
        injectSummary(sessionId, "session:memory:l2:", "Medium-range session summary", assembledMessages);

        List<ChatMessage> l1Messages = (List<ChatMessage>) redisTemplate.opsForValue().get("session:memory:l1:" + sessionId);
        if (l1Messages != null && !l1Messages.isEmpty()) {
            assembledMessages.addAll(l1Messages);
        }

        return assembledMessages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        log.info("Updating memory for session: {}. Messages count: {}", sessionId, messages.size());

        List<ChatMessage> snapshot = limitToRecent(messages);
        List<ChatMessage> recentMessages = new ArrayList<>(snapshot.subList(Math.max(0, snapshot.size() - l1Limit), snapshot.size()));
        redisTemplate.opsForValue().set("session:memory:l1:" + sessionId, recentMessages, 12, TimeUnit.HOURS);

        if (snapshot.size() > l1Limit) {
            Thread.startVirtualThread(() -> refreshSummaries(sessionId, snapshot));
        } else {
            redisTemplate.delete("session:memory:l2:" + sessionId);
            redisTemplate.delete("session:memory:l3:" + sessionId);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("Deleting memory for session: {}", sessionId);
        redisTemplate.delete("session:memory:l1:" + sessionId);
        redisTemplate.delete("session:memory:l2:" + sessionId);
        redisTemplate.delete("session:memory:l3:" + sessionId);
    }

    private void injectUserPreferences(String sessionId, List<ChatMessage> target) {
        ChatSession session = findSession(sessionId);
        if (session == null) {
            return;
        }

        List<UserGlobalMemory> preferences = userGlobalMemoryMapper.selectList(
                new QueryWrapper<UserGlobalMemory>().eq("user_id", session.getUserId())
        );
        if (!preferences.isEmpty()) {
            String globalMemStr = "Here are long-term facts/preferences you must remember about this user:\n" +
                    preferences.stream()
                            .map(p -> "- " + p.getPreferenceKey() + ": " + p.getPreferenceValue())
                            .collect(Collectors.joining("\n"));
            target.add(SystemMessage.from(globalMemStr));
        }
    }

    private void injectSummary(String sessionId, String prefix, String title, List<ChatMessage> target) {
        Object summary = redisTemplate.opsForValue().get(prefix + sessionId);
        if (summary instanceof String text && !text.isBlank()) {
            target.add(SystemMessage.from(title + ":\n" + text));
        }
    }

    private List<ChatMessage> limitToRecent(List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) {
            return List.copyOf(messages);
        }
        return List.copyOf(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    private void refreshSummaries(String sessionId, List<ChatMessage> messages) {
        try {
            String l2Summary = summarize(messages.subList(0, Math.max(0, messages.size() - l1Limit)),
                    "Summarize the dialogue in concise Chinese bullet-free prose, focusing on user intent and factual context.");
            redisTemplate.opsForValue().set("session:memory:l2:" + sessionId, l2Summary, 12, TimeUnit.HOURS);

            String l3Summary = null;
            if (messages.size() > l2Limit) {
                l3Summary = summarize(messages.subList(0, Math.max(0, messages.size() - l2Limit)),
                        "Compress the dialogue into a very short Chinese summary of durable facts, decisions, and entities only.");
                redisTemplate.opsForValue().set("session:memory:l3:" + sessionId, l3Summary, 12, TimeUnit.HOURS);
            }

            persistCompressionState(sessionId, l2Summary, l3Summary);
        } catch (Exception exception) {
            log.warn("Failed to refresh hierarchical summaries for session {}", sessionId, exception);
        }
    }

    private String summarize(List<ChatMessage> messages, String instruction) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        String transcript = messages.stream()
                .map(this::formatMessage)
                .collect(Collectors.joining("\n"));

        String prompt = instruction + "\n\nConversation:\n" + transcript;
        return chatLanguageModel.generate(prompt).trim();
    }

    private String formatMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "user: " + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return "assistant: " + aiMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolMessage) {
            return "tool: " + toolMessage.text();
        }
        if (message instanceof SystemMessage systemMessage) {
            return "system: " + systemMessage.text();
        }
        return message.type() + ": " + message.toString();
    }

    private void persistCompressionState(String sessionId, String l2Summary, String l3Summary) {
        List<com.yoswell.agenticrag.platform.session.entity.ChatMessage> persistedMessages = chatMessageMapper.selectList(
                new QueryWrapper<com.yoswell.agenticrag.platform.session.entity.ChatMessage>()
                        .eq("session_id", sessionId)
                        .orderByAsc("created_at")
        );

        int total = persistedMessages.size();
        int l1Start = Math.max(0, total - l1Limit);
        int l2Start = Math.max(0, total - l2Limit);

        for (int index = 0; index < total; index++) {
            com.yoswell.agenticrag.platform.session.entity.ChatMessage persisted = persistedMessages.get(index);
            if (index >= l1Start) {
                persisted.setCompressionLevel("L1");
                persisted.setCompressedContent(null);
            } else if (index >= l2Start || l3Summary == null || l3Summary.isBlank()) {
                persisted.setCompressionLevel("L2");
                persisted.setCompressedContent(l2Summary);
            } else {
                persisted.setCompressionLevel("L3");
                persisted.setCompressedContent(l3Summary);
            }
            chatMessageMapper.updateById(persisted);
        }

        if (l3Summary != null && !l3Summary.isBlank()) {
            ChatSession session = findSession(sessionId);
            if (session != null) {
                session.setSummary(l3Summary);
                chatSessionMapper.updateById(session);
            }
        }
    }

    private ChatSession findSession(String sessionId) {
        return chatSessionMapper.selectOne(new QueryWrapper<ChatSession>().eq("session_id", sessionId));
    }
}
