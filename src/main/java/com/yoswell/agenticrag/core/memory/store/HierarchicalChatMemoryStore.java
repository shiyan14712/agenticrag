package com.yoswell.agenticrag.core.memory.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.core.memory.constants.MemoryStoreConstants;
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

/**
 * 分层聊天内存存储器
 * 
 * <p>实现三级分层内存管理策略，平衡对话质量与存储效率：</p>
 * 
 * <h3>内存层级架构：</h3>
 * <ul>
 *     <li><strong>L1 级（热数据层）</strong>：存储最近 N 条原始对话消息（Redis），保持最高精度</li>
 *     <li><strong>L2 级（温数据层）</strong>：存储中期对话摘要（Redis），平衡精度与容量</li>
 *     <li><strong>L3 级（冷数据层）</strong>：存储长期对话核心摘要（Redis + DB），保留最关键信息</li>
 * </ul>
 * 
 * <h3>核心特性：</h3>
 * <ul>
 *     <li>支持用户全局偏好注入（从数据库加载）</li>
 *     <li>智能消息序列化/反序列化（兼容多种数据格式）</li>
 *     <li>虚拟线程异步摘要生成（不阻塞主流程）</li>
 *     <li>压缩状态持久化（DB 记录每段消息的压缩级别）</li>
 * </ul>
 * 
 * <h3>工作流程：</h3>
 * <ol>
 *     <li>{@code getMessages()}：组装 L3 摘要 → L2 摘要 → L1 原始消息 → 用户偏好</li>
 *     <li>{@code updateMessages()}：更新 L1 缓存 → 触发虚拟线程生成 L2/L3 摘要 → 持久化压缩状态</li>
 * </ol>
 * 
 * @author AgenticRAG Team
 * @version 1.0
 * @since 2026-03-31
 * 
 * @see ChatMemoryStore
 * @see MemoryStoreConstants
 */
@Component
public class HierarchicalChatMemoryStore implements ChatMemoryStore {

    /**
     * 日志记录器
     */
    private static final Logger log = LoggerFactory.getLogger(HierarchicalChatMemoryStore.class);

    /**
     * Redis 模板，用于操作三级内存缓存
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 用户全局记忆 Mapper，用于加载用户偏好设置
     */
    private final UserGlobalMemoryMapper userGlobalMemoryMapper;

    /**
     * 会话 Mapper，用于查询和更新会话信息
     */
    private final ChatSessionMapper chatSessionMapper;

    /**
     * 消息 Mapper，用于持久化压缩状态到数据库
     */
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 聊天语言模型，用于生成对话摘要
     */
    private final ChatLanguageModel chatLanguageModel;

    /**
     * JSON 序列化器，处理 Redis 数据的序列化/反序列化
     */
    private final ObjectMapper objectMapper;

    /**
     * 最大保留消息数，超出此值将触发分级压缩
     */
    private final int maxMessages;

    /**
     * L1 级缓存消息上限，超过后触发 L2 摘要生成
     */
    private final int l1Limit;

    /**
     * L2 级缓存消息上限，超过后触发 L3 摘要生成
     */
    private final int l2Limit;

    /**
     * 构造函数
     * 
     * @param redisTemplate         Redis 操作模板
     * @param userGlobalMemoryMapper 用户全局记忆 Mapper
     * @param chatSessionMapper      会话 Mapper
     * @param chatMessageMapper      消息 Mapper
     * @param chatLanguageModel      聊天语言模型（用于摘要生成）
     * @param maxMessages            最大保留消息数（默认 40 条）
     * @param l1Limit                L1 级缓存上限（默认 10 条）
     * @param l2Limit                L2 级缓存上限（默认 30 条）
     */
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
        this.objectMapper = new ObjectMapper();
        this.maxMessages = maxMessages;
        this.l1Limit = l1Limit;
        this.l2Limit = l2Limit;
    }

    /**
     * 获取指定会话的聊天消息列表
     * 
     * <p>按照 L3 → L2 → L1 的顺序组装消息：</p>
     * <ol>
     *     <li>注入用户全局偏好（作为 SystemMessage）</li>
     *     <li>注入 L3 长期摘要（如果有）</li>
     *     <li>注入 L2 中期摘要（如果有）</li>
     *     <li>注入 L1 原始消息（从 Redis 读取）</li>
     * </ol>
     * 
     * @param memoryId 内存标识符（即 session ID）
     * @return 组装后的聊天消息列表
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L1
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("Retrieving memory for session: {}", sessionId);

        ArrayList<ChatMessage> assembledMessages = new ArrayList<>();
        injectUserPreferences(sessionId, assembledMessages);
        injectSummary(sessionId, "session:memory:l3:", "Long-range session summary", assembledMessages);
        injectSummary(sessionId, "session:memory:l2:", "Medium-range session summary", assembledMessages);

        // 从 Redis 获取 L1 原始消息，支持多种数据格式的自动转换
        Object l1Data = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
        if (l1Data != null) {
            List<ChatMessage> l1Messages = deserializeChatMessages(l1Data);
            if (!l1Messages.isEmpty()) {
                assembledMessages.addAll(l1Messages);
            }
        }

        return assembledMessages;
    }

    /**
     * 更新指定会话的聊天消息列表
     * 
     * <p>执行以下操作：</p>
     * <ol>
     *     <li>截取最近 N 条消息（不超过 maxMessages）</li>
     *     <li>将最新 L1_LIMIT 条消息存入 Redis L1 缓存</li>
     *     <li>如果总消息数超过 L1_LIMIT，启动虚拟线程异步生成 L2/L3 摘要</li>
     *     <li>如果未达阈值，删除旧的 L2/L3 摘要</li>
     * </ol>
     * 
     * @param memoryId  内存标识符（即 session ID）
     * @param messages  完整的聊天消息列表
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L1
     * @see MemoryStoreConstants#L1_CACHE_TTL_HOURS
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();
        log.info("Updating memory for session: {}. Messages count: {}", sessionId, messages.size());

        List<ChatMessage> snapshot = limitToRecent(messages);
        List<ChatMessage> recentMessages = new ArrayList<>(snapshot.subList(Math.max(0, snapshot.size() - l1Limit), snapshot.size()));
        
        // 关键修复：确保至少保存一条消息，避免空列表导致 OpenAI API 错误
        if (recentMessages.isEmpty() && !messages.isEmpty()) {
            log.warn("Messages list is empty after limiting (limit={}, snapshot={}), saving original messages instead", l1Limit, snapshot.size());
            recentMessages = new ArrayList<>(messages);
        }
        
        log.debug("Saving {} messages to Redis L1 cache", recentMessages.size());
        // 将最新 L1_LIMIT 条消息存入 Redis，设置 12 小时过期时间
        try {
            String l1Json = dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(recentMessages);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId, l1Json, MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to serialize recent messages to JSON: ", e);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId, recentMessages, MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        if (snapshot.size() > l1Limit) {
            Thread.startVirtualThread(() -> refreshSummaries(sessionId, snapshot));
        } else {
            redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId);
            redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId);
        }
    }

    /**
     * 删除指定会话的所有内存数据
     * 
     * <p>清理 Redis 中的 L1、L2、L3 三级缓存</p>
     * 
     * @param memoryId  内存标识符（即 session ID）
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L1
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("Deleting memory for session: {}", sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId);
    }

    /**
     * 注入用户全局偏好设置
     * 
     * <p>从数据库加载用户的全局记忆/偏好，构造成 SystemMessage 注入到对话上下文中。</p>
     * <p>这些偏好会永久影响 AI 的回复风格和内容。</p>
     * 
     * @param sessionId  会话 ID
     * @param target     目标消息列表（用于添加 SystemMessage）
     * 
     * @see MemoryStoreConstants#GLOBAL_MEMORY_PREFIX
     * @see MemoryStoreConstants#GLOBAL_MEMORY_ITEM_PREFIX
     * @see MemoryStoreConstants#GLOBAL_MEMORY_KV_SEPARATOR
     */
    private void injectUserPreferences(String sessionId, List<ChatMessage> target) {
        ChatSession session = findSession(sessionId);
        if (session == null) {
            return;
        }

        List<UserGlobalMemory> preferences = userGlobalMemoryMapper.selectList(
                new QueryWrapper<UserGlobalMemory>().eq("user_id", session.getUserId())
        );
        if (!preferences.isEmpty()) {
            String globalMemStr = MemoryStoreConstants.GLOBAL_MEMORY_PREFIX +
                    preferences.stream()
                            .map(p -> MemoryStoreConstants.GLOBAL_MEMORY_ITEM_PREFIX + 
                                      p.getPreferenceKey() + 
                                      MemoryStoreConstants.GLOBAL_MEMORY_KV_SEPARATOR + 
                                      p.getPreferenceValue())
                            .collect(Collectors.joining("\n"));
            target.add(SystemMessage.from(globalMemStr));
        }
    }

    /**
     * 注入会话摘要
     * 
     * <p>从 Redis 读取指定前缀的摘要内容，构造成 SystemMessage 注入到对话上下文中。</p>
     * 
     * @param sessionId  会话 ID
     * @param prefix     Redis 键前缀（如 "session:memory:l2:"）
     * @param title      摘要标题（用于构建 SystemMessage）
     * @param target     目标消息列表
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     */
    private void injectSummary(String sessionId, String prefix, String title, List<ChatMessage> target) {
        Object summary = redisTemplate.opsForValue().get(prefix + sessionId);
        if (summary instanceof String text && !text.isBlank()) {
            target.add(SystemMessage.from(title + ":\n" + text));
        }
    }

    /**
     * 截取最近的消息
     * 
     * <p>如果消息总数超过最大值，则只保留最近的 maxMessages 条</p>
     * 
     * @param messages  原始消息列表
     * @return 截取后的消息列表
     */
    private List<ChatMessage> limitToRecent(List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) {
            return List.copyOf(messages);
        }
        return List.copyOf(messages.subList(messages.size() - maxMessages, messages.size()));
    }

    /**
     * 刷新分层摘要（在虚拟线程中执行）
     * 
     * <p>核心业务流程：</p>
     * <ol>
     *     <li>对 L1_LIMIT 之前的消息生成 L2 摘要（中期压缩）</li>
     *     <li>对 L2_LIMIT 之前的消息生成 L3 摘要（长期压缩）</li>
     *     <li>将摘要存入 Redis 对应层级</li>
     *     <li>调用 {@code persistCompressionState()} 持久化压缩状态到数据库</li>
     * </ol>
     * 
     * @param sessionId  会话 ID
     * @param messages   完整的历史消息列表
     * 
     * @see MemoryStoreConstants#L2_SUMMARY_PROMPT
     * @see MemoryStoreConstants#L3_SUMMARY_PROMPT
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     */
    private void refreshSummaries(String sessionId, List<ChatMessage> messages) {
        try {
            String l2Summary = summarize(messages.subList(0, Math.max(0, messages.size() - l1Limit)),
                    MemoryStoreConstants.L2_SUMMARY_PROMPT);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId, l2Summary, MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);

            String l3Summary = null;
            if (messages.size() > l2Limit) {
                l3Summary = summarize(messages.subList(0, Math.max(0, messages.size() - l2Limit)), MemoryStoreConstants.L3_SUMMARY_PROMPT);
                redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId, l3Summary, MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);
            }

            persistCompressionState(sessionId, l2Summary, l3Summary);
        } catch (Exception exception) {
            log.warn("Failed to refresh hierarchical summaries for session {}", sessionId, exception);
        }
    }

    /**
     * 生成对话摘要
     * 
     * <p>使用 LLM 对给定消息生成指定指令的摘要。</p>
     * <p>摘要 Prompt 由 instruction + 对话转录组成。</p>
     * 
     * @param messages     需要总结的消息列表
     * @param instruction  摘要生成指令（如 L2/L3 专用 Prompt）
     * @return 生成的摘要文本
     * 
     * @see MemoryStoreConstants#L2_SUMMARY_PROMPT
     * @see MemoryStoreConstants#L3_SUMMARY_PROMPT
     */
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

    /**
     * 格式化单条消息
     * 
     * <p>将 ChatMessage 对象转换为可读的文本格式，用于 LLM 摘要生成。</p>
     * <p>根据消息类型添加不同的前缀标识。</p>
     * 
     * @param message  聊天消息对象
     * @return 格式化后的文本
     * 
     * @see MemoryStoreConstants#USER_MESSAGE_PREFIX
     * @see MemoryStoreConstants#ASSISTANT_MESSAGE_PREFIX
     * @see MemoryStoreConstants#TOOL_MESSAGE_PREFIX
     * @see MemoryStoreConstants#SYSTEM_MESSAGE_PREFIX
     */
    private String formatMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return MemoryStoreConstants.USER_MESSAGE_PREFIX + userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return MemoryStoreConstants.ASSISTANT_MESSAGE_PREFIX + aiMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolMessage) {
            return MemoryStoreConstants.TOOL_MESSAGE_PREFIX + toolMessage.text();
        }
        if (message instanceof SystemMessage systemMessage) {
            return MemoryStoreConstants.SYSTEM_MESSAGE_PREFIX + systemMessage.text();
        }
        return message.type() + ": " + message.toString();
    }

    /**
     * 持久化压缩状态到数据库
     * 
     * <p>为每条历史消息标记压缩级别并保存：</p>
     * <ul>
     *     <li><strong>L1</strong>：最近消息，无压缩，compressedContent = null</li>
     *     <li><strong>L2</strong>：中期消息，使用 L2 摘要压缩</li>
     *     <li><strong>L3</strong>：长期消息，使用 L3 摘要压缩</li>
     * </ul>
     * <p>同时更新会话表的 summary 字段（如果有 L3 摘要）。</p>
     * 
     * @param sessionId   会话 ID
     * @param l2Summary   L2 级摘要内容
     * @param l3Summary   L3 级摘要内容（可为 null）
     * 
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L1
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L2
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L3
     */
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
                persisted.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1);
                persisted.setCompressedContent(null);
            } else if (index >= l2Start || l3Summary == null || l3Summary.isBlank()) {
                persisted.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L2);
                persisted.setCompressedContent(l2Summary);
            } else {
                persisted.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L3);
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

    /**
     * 智能反序列化聊天消息列表
     * 
     * <p>解决 Redis 序列化不一致问题的核心方法。支持三种数据源：</p>
     * <ol>
     *     <li><strong>List&lt;?&gt;</strong>：检查元素类型，直接返回或转换</li>
     *     <li><strong>String（JSON）</strong>：使用 ObjectMapper 反序列化</li>
     *     <li><strong>其他类型</strong>：记录警告并返回空列表</li>
     * </ol>
     * 
     * <p><strong>容错机制：</strong></p>
     * <ul>
     *     <li>JSON 解析失败时返回空列表而非抛异常</li>
     *     <li>未知类型优雅降级，不影响主流程</li>
     *     <li>详细日志记录，便于问题排查</li>
     *     <li>空列表或无效数据直接过滤</li>
     * </ul>
     * 
     * @param data  Redis 中读取的原始数据
     * @return 反序列化后的 ChatMessage 列表
     * 
     * @see #convertToChatMessage(Object)
     */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> deserializeChatMessages(Object data) {
        if (data == null) {
            return new ArrayList<>();
        }
        
        if (data instanceof String jsonString) {
            // 如果是 JSON 字符串，使用 Langchain4j 反序列化
            try {
                log.debug("Deserializing JSON string to List<ChatMessage>");
                return dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson(jsonString);
            } catch (Exception e) {
                log.warn("Failed to deserialize ChatMessage list from JSON: {}", jsonString, e);
                return new ArrayList<>();
            }
        } else if (data instanceof List<?> list) {
            // 如果是空列表，直接返回
            if (list.isEmpty()) {
                log.debug("Received empty list from Redis, returning empty ChatMessage list");
                return new ArrayList<>();
            }
            
            // 如果已经是 ChatMessage 列表，直接返回
            if (list.get(0) instanceof ChatMessage) {
                log.debug("Data is already a List<ChatMessage>, returning directly");
                return (List<ChatMessage>) list;
            }
            
            // 如果是其他类型的列表，尝试逐个转换并过滤无效数据
            log.debug("Converting List of {} to List<ChatMessage>", list.get(0).getClass().getSimpleName());
            return list.stream()
                    .map(this::convertToChatMessage)
                    .filter(msg -> msg != null)
                    .collect(Collectors.toList());
        } else {
            log.warn("Unexpected data type for ChatMessage list: {} (class: {})", 
                data, data.getClass().getName());
            return new ArrayList<>();
        }
    }

    /**
     * 将任意对象转换为 ChatMessage
     * 
     * <p>支持多种输入类型的智能转换：</p>
     * <ul>
     *     <li><strong>ChatMessage 子类</strong>：直接返回原对象</li>
     *     <li><strong>String</strong>：包装为 UserMessage（纯文本输入）</li>
     *     <li><strong>Map<?,?></strong>：检查是否包含消息字段，有则转换</li>
     *     <li><strong>其他类型</strong>：记录警告并返回 null</li>
     * </ul>
     * 
     * <p><strong>典型应用场景：</strong></p>
     * <ol>
     *     <li>Redis 数据格式不一致时的兜底转换</li>
     *     <li>历史数据迁移时的格式适配</li>
     *     <li>第三方系统集成的数据兼容</li>
     * </ol>
     * 
     * @param item  待转换的对象
     * @return 转换后的 ChatMessage，失败返回 null
     * 
     * @see UserMessage#from(String)
     */
    private ChatMessage convertToChatMessage(Object item) {
        if (item == null) {
            log.debug("Cannot convert null to ChatMessage, returning null");
            return null;
        }
        
        if (item instanceof ChatMessage chatMessage) {
            return chatMessage;
        } else if (item instanceof String text) {
            // 如果是纯文本，当作 UserMessage 处理
            if (text.isBlank()) {
                log.debug("Skipping blank string, returning null");
                return null;
            }
            log.debug("Converting String to UserMessage: {}", text.substring(0, Math.min(50, text.length())));
            return UserMessage.from(text);
        } else if (item instanceof Map<?, ?> map) {
            // 如果是空 Map，直接返回 null
            if (map.isEmpty()) {
                log.debug("Skipping empty Map, returning null");
                return null;
            }
            
            // 如果是 Map，先检查是否包含消息类型信息
            try {
                String json = objectMapper.writeValueAsString(map);
                log.debug("Converting Map to ChatMessage: {}", json.substring(0, Math.min(100, json.length())));
                return dev.langchain4j.data.message.ChatMessageDeserializer.messageFromJson(json);
            } catch (Exception e) {
                log.warn("Failed to convert Map to ChatMessage: {}", map, e);
                return null;
            }
        } else {
            log.warn("Cannot convert object to ChatMessage: {} (class: {})", 
                item, item.getClass().getName());
            return null;
        }
    }
}
