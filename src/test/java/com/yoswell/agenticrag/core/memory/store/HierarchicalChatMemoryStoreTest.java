package com.yoswell.agenticrag.core.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

@ExtendWith(MockitoExtension.class)
class HierarchicalChatMemoryStoreTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private UserGlobalMemoryMapper userGlobalMemoryMapper;

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    private HierarchicalChatMemoryStore hierarchicalChatMemoryStore;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        hierarchicalChatMemoryStore = new HierarchicalChatMemoryStore(
                redisTemplate,
                userGlobalMemoryMapper,
                chatSessionMapper,
                chatMessageMapper,
                chatLanguageModel,
                10,
                2,
                3
        );
    }

    @Test
    void getMessagesLoadsUserPreferencesUsingSessionLookup() {
        ChatSession session = new ChatSession();
        session.setSessionId("session-1");
        session.setUserId("usrIdTest");
        when(chatSessionMapper.selectOne(any())).thenReturn(session);

        UserGlobalMemory memory = new UserGlobalMemory();
        memory.setPreferenceKey("language");
        memory.setPreferenceValue("zh-CN");
        when(userGlobalMemoryMapper.selectList(any())).thenReturn(List.of(memory));

        List<ChatMessage> messages = hierarchicalChatMemoryStore.getMessages("session-1");

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("language: zh-CN");
    }

    @Test
    void updateMessagesRefreshesSummariesAndPersistsCompressionState() {
        when(chatLanguageModel.generate(any(String.class))).thenReturn("L2 summary", "L3 summary");

        com.yoswell.agenticrag.platform.session.entity.ChatMessage persisted1 = new com.yoswell.agenticrag.platform.session.entity.ChatMessage();
        persisted1.setId(1L);
        com.yoswell.agenticrag.platform.session.entity.ChatMessage persisted2 = new com.yoswell.agenticrag.platform.session.entity.ChatMessage();
        persisted2.setId(2L);
        com.yoswell.agenticrag.platform.session.entity.ChatMessage persisted3 = new com.yoswell.agenticrag.platform.session.entity.ChatMessage();
        persisted3.setId(3L);
        com.yoswell.agenticrag.platform.session.entity.ChatMessage persisted4 = new com.yoswell.agenticrag.platform.session.entity.ChatMessage();
        persisted4.setId(4L);
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(persisted1, persisted2, persisted3, persisted4));

        ChatSession session = new ChatSession();
        session.setId(10L);
        session.setSessionId("session-1");
        session.setUserId("usrIdTest");
        when(chatSessionMapper.selectOne(any())).thenReturn(session);

        hierarchicalChatMemoryStore.updateMessages("session-1", List.of(
                UserMessage.from("hello"),
                AiMessage.from("hello, I can help"),
                UserMessage.from("please summarize"),
                AiMessage.from("sure")
        ));

        verify(chatMessageMapper, timeout(1000).atLeastOnce()).updateById(
                org.mockito.ArgumentMatchers.<com.yoswell.agenticrag.platform.session.entity.ChatMessage>any()
        );
        verify(chatSessionMapper, timeout(1000)).updateById(
                org.mockito.ArgumentMatchers.<ChatSession>argThat(updated -> "L3 summary".equals(updated.getSummary()))
        );
    }
}
