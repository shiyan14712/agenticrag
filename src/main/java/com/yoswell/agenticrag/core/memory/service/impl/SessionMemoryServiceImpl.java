package com.yoswell.agenticrag.core.memory.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.core.memory.constants.MemoryStoreConstants;
import com.yoswell.agenticrag.core.memory.dto.ChatMessageDTO;
import com.yoswell.agenticrag.core.memory.dto.SessionMemoryViewDTO;
import com.yoswell.agenticrag.core.memory.service.SessionMemoryService;
import com.yoswell.agenticrag.core.memory.store.HierarchicalChatMemoryStore;
import com.yoswell.agenticrag.platform.session.service.JudgeSessionService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionMemoryServiceImpl implements SessionMemoryService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final HierarchicalChatMemoryStore chatMemoryStore;
    private final JudgeSessionService judgeSessionService;
    private final ObjectMapper objectMapper;

    @Override
    public SessionMemoryViewDTO getSessionMemoryLayers(String sessionId) {
        judgeSessionService.validateSessionExists(sessionId);
        log.info("[SessionMemoryService] 查询会话分层记忆开始: sessionId={}", sessionId);
        SessionMemoryViewDTO dto = new SessionMemoryViewDTO();

        Object l3SummaryData = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId);
        dto.setL3Summary(readSummary(sessionId, "L3", l3SummaryData));

        Object l2SummaryData = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId);
        dto.setL2Summary(readSummary(sessionId, "L2", l2SummaryData));

        Object l1Data = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
        log.info("[SessionMemoryService] 读取 L1 原始记忆: sessionId={}, dataType={}", sessionId, describeType(l1Data));
        List<ChatMessage> l1Messages = parseL1Messages(sessionId, l1Data);
        dto.setL1Messages(l1Messages.stream().map(ChatMessageDTO::from).collect(Collectors.toList()));

        log.info("[SessionMemoryService] 查询会话分层记忆完成: sessionId={}, l1Count={}, hasL2={}, hasL3={}",
                sessionId,
                l1Messages.size(),
                hasText(dto.getL2Summary()),
                hasText(dto.getL3Summary()));
        if (l1Messages.isEmpty() && !hasText(dto.getL2Summary()) && !hasText(dto.getL3Summary())) {
            log.warn("[SessionMemoryService] 会话分层记忆为空: sessionId={}", sessionId);
        }

        return dto;
    }

    @Override
    public List<ChatMessageDTO> getAssembledContext(String sessionId) {
        judgeSessionService.validateSessionExists(sessionId);
        log.info("[SessionMemoryService] 查询会话组装上下文开始: sessionId={}", sessionId);
        List<ChatMessage> assembledMessages = chatMemoryStore.getMessages(sessionId);
        if (assembledMessages == null) {
            log.warn("[SessionMemoryService] 会话组装上下文为空: sessionId={}", sessionId);
            return new ArrayList<>();
        }
        List<ChatMessageDTO> context = assembledMessages.stream().map(ChatMessageDTO::from).collect(Collectors.toList());
        log.info("[SessionMemoryService] 查询会话组装上下文完成: sessionId={}, contextSize={}", sessionId, context.size());
        return context;
    }

    private String readSummary(String sessionId, String layer, Object summaryData) {
        if (summaryData == null) {
            log.info("[SessionMemoryService] {} 记忆未命中: sessionId={}", layer, sessionId);
            return null;
        }
        if (summaryData instanceof String summary && hasText(summary)) {
            log.info("[SessionMemoryService] {} 记忆命中: sessionId={}, length={}", layer, sessionId, summary.length());
            return summary;
        }
        log.warn("[SessionMemoryService] {} 记忆数据类型异常: sessionId={}, dataType={}",
                layer, sessionId, describeType(summaryData));
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<ChatMessage> parseL1Messages(String sessionId, Object data) {
        if (data == null) {
            log.warn("[SessionMemoryService] L1 记忆未命中: sessionId={}", sessionId);
            return new ArrayList<>();
        }

        if (data instanceof String jsonString) {
            try {
                List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(jsonString);
                log.info("[SessionMemoryService] L1 JSON 反序列化成功: sessionId={}, messageCount={}",
                        sessionId, messages.size());
                return messages;
            } catch (Exception exception) {
                log.warn("[SessionMemoryService] L1 JSON 反序列化失败: sessionId={}, payloadLength={}",
                        sessionId, jsonString.length(), exception);
                return new ArrayList<>();
            }
        }

        if (data instanceof List<?> list) {
            if (list.isEmpty()) {
                log.info("[SessionMemoryService] L1 命中空列表: sessionId={}", sessionId);
                return new ArrayList<>();
            }
            if (list.get(0) instanceof ChatMessage) {
                log.info("[SessionMemoryService] L1 直接命中 ChatMessage 列表: sessionId={}, messageCount={}",
                        sessionId, list.size());
                return (List<ChatMessage>) list;
            }

            List<ChatMessage> converted = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                ChatMessage message = convertToChatMessage(sessionId, list.get(index), index);
                if (message != null) {
                    converted.add(message);
                }
            }

            log.info("[SessionMemoryService] L1 列表兼容转换完成: sessionId={}, rawSize={}, messageCount={}",
                    sessionId, list.size(), converted.size());
            if (converted.isEmpty()) {
                log.warn("[SessionMemoryService] L1 列表兼容转换后为空: sessionId={}, rawElementType={}",
                        sessionId, describeType(list.get(0)));
            }
            return converted;
        }

        if (data instanceof Map<?, ?> map) {
            ChatMessage message = convertMapToChatMessage(sessionId, map, 0);
            if (message != null) {
                log.info("[SessionMemoryService] L1 单条 Map 兼容转换成功: sessionId={}", sessionId);
                return new ArrayList<>(List.of(message));
            }
            log.warn("[SessionMemoryService] L1 单条 Map 兼容转换失败: sessionId={}, keys={}", sessionId, map.keySet());
            return new ArrayList<>();
        }

        log.warn("[SessionMemoryService] L1 数据类型不支持: sessionId={}, dataType={}", sessionId, describeType(data));
        return new ArrayList<>();
    }

    private ChatMessage convertToChatMessage(String sessionId, Object item, int index) {
        if (item == null) {
            log.warn("[SessionMemoryService] L1 列表元素为空: sessionId={}, index={}", sessionId, index);
            return null;
        }
        if (item instanceof ChatMessage chatMessage) {
            return chatMessage;
        }
        if (item instanceof Map<?, ?> map) {
            return convertMapToChatMessage(sessionId, map, index);
        }
        if (item instanceof String text) {
            if (!hasText(text)) {
                log.warn("[SessionMemoryService] L1 字符串元素为空白: sessionId={}, index={}", sessionId, index);
                return null;
            }
            try {
                return ChatMessageDeserializer.messageFromJson(text);
            } catch (Exception exception) {
                log.warn("[SessionMemoryService] L1 字符串元素不是标准消息 JSON，按用户消息降级: sessionId={}, index={}",
                        sessionId, index, exception);
                return UserMessage.from(text);
            }
        }

        log.warn("[SessionMemoryService] L1 列表元素类型不支持: sessionId={}, index={}, dataType={}",
                sessionId, index, describeType(item));
        return null;
    }

    private ChatMessage convertMapToChatMessage(String sessionId, Map<?, ?> map, int index) {
        if (map.isEmpty()) {
            log.warn("[SessionMemoryService] L1 Map 元素为空: sessionId={}, index={}", sessionId, index);
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(map);
            return ChatMessageDeserializer.messageFromJson(json);
        } catch (Exception exception) {
            ChatMessage fallbackMessage = convertSimplifiedMapToChatMessage(map);
            if (fallbackMessage != null) {
                log.warn("[SessionMemoryService] L1 Map 元素按简化结构兼容转换: sessionId={}, index={}, keys={}",
                        sessionId, index, map.keySet());
                return fallbackMessage;
            }
            log.warn("[SessionMemoryService] L1 Map 元素转换失败: sessionId={}, index={}, keys={}",
                    sessionId, index, map.keySet(), exception);
            return null;
        }
    }

    private ChatMessage convertSimplifiedMapToChatMessage(Map<?, ?> map) {
        String type = readString(map, "type", "messageType", "@type");
        String text = readString(map, "text", "content", "message", "value");
        if (!hasText(type) || !hasText(text)) {
            return null;
        }

        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "SYSTEM" -> SystemMessage.from(text);
            case "USER" -> UserMessage.from(text);
            case "AI", "ASSISTANT" -> AiMessage.from(text);
            case "TOOL", "TOOL_EXECUTION_RESULT" -> ToolExecutionResultMessage.from(
                    defaultString(readString(map, "id", "toolExecutionId"), ""),
                    defaultString(readString(map, "toolName", "name"), "unknown"),
                    text);
            default -> null;
        };
    }

    private String readString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                if (hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private String defaultString(String value, String defaultValue) {
        return hasText(value) ? value : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String describeType(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
