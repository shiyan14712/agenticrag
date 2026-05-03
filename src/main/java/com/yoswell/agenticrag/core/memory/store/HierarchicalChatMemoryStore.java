package com.yoswell.agenticrag.core.memory.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.alibaba.ttl.TtlRunnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.BusinessExceptionMapper;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.prompt.SystemPromptAssembler;
import com.yoswell.agenticrag.core.memory.constants.MemoryStoreConstants;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.platform.session.entity.ChatMessageDO;
import com.yoswell.agenticrag.platform.session.entity.ChatSessionDO;
import com.yoswell.agenticrag.platform.session.mapper.ChatMessageMapper;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * 分层聊天内存存储器
 * 
 * <p>
 * 实现三级分层内存管理策略，平衡对话质量与存储效率：
 * </p>
 * <p>
 * 扮演了一个**适配器（Adapter）**的核心枢纽角色。它的职责恰恰就是在两种形态之间做”翻译”和”组装”。
 * 动态上下文（用户偏好、L2/L3 摘要）的组装委托给 {@link com.yoswell.agenticrag.core.agent.prompt.SystemPromptAssembler}，
 * 确保发送给模型的消息列表中始终只有一条 SystemMessage。
 * </p>
 * 
 * <h3>内存层级架构：</h3>
 * <ul>
 * <li><strong>L1 级（热数据层）</strong>：存储最近 N 条原始对话消息（Redis），保持最高精度</li>
 * <li><strong>L2 级（温数据层）</strong>：存储中期对话摘要（Redis），平衡精度与容量</li>
 * <li><strong>L3 级（冷数据层）</strong>：存储长期对话核心摘要（Redis + DB），保留最关键信息</li>
 * </ul>
 * 
 * <h3>核心特性：</h3>
 * <ul>
 * <li>支持用户全局偏好注入（从数据库加载）</li>
 * <li>智能消息序列化/反序列化（兼容多种数据格式）</li>
 * <li>虚拟线程异步摘要生成（不阻塞主流程）</li>
 * <li>压缩状态持久化（DB 记录每段消息的压缩级别）</li>
 * </ul>
 * 
 * <h3>工作流程：</h3>
 * <ol>
 * <li>{@code getMessages()}：组装 L3 摘要 → L2 摘要 → L1 原始消息 → 用户偏好</li>
 * <li>{@code updateMessages()}：更新 L1 缓存 → 触发虚拟线程生成 L2/L3 摘要 → 持久化压缩状态</li>
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
    private final ChatModel chatLanguageModel;

    /**
     * 系统提示词组装器，负责将静态指令与动态上下文合并为单条 SystemMessage
     */
    private final SystemPromptAssembler systemPromptAssembler;

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
     * @param redisTemplate          Redis 操作模板
     * @param userGlobalMemoryMapper 用户全局记忆 Mapper
     * @param chatSessionMapper      会话 Mapper
     * @param chatMessageMapper      消息 Mapper
     * @param chatLanguageModel      聊天语言模型（用于摘要生成）
     * @param systemPromptAssembler  系统提示词组装器
     * @param maxMessages            最大保留消息数（默认 40 条）
     * @param l1Limit                L1 级缓存上限（默认 10 条）
     * @param l2Limit                L2 级缓存上限（默认 30 条）
     */
    public HierarchicalChatMemoryStore(RedisTemplate<String, Object> redisTemplate,
            UserGlobalMemoryMapper userGlobalMemoryMapper,
            ChatSessionMapper chatSessionMapper,
            ChatMessageMapper chatMessageMapper,
            ChatModel chatLanguageModel,
            SystemPromptAssembler systemPromptAssembler,
            @Value("${rag.memory.max-messages:40}") int maxMessages,
            @Value("${rag.memory.l1-limit:10}") int l1Limit,
            @Value("${rag.memory.l2-limit:30}") int l2Limit) {
        this.redisTemplate = redisTemplate;
        this.userGlobalMemoryMapper = userGlobalMemoryMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatLanguageModel = chatLanguageModel;
        this.systemPromptAssembler = systemPromptAssembler;
        this.objectMapper = new ObjectMapper();
        this.maxMessages = maxMessages;
        this.l1Limit = l1Limit;
        this.l2Limit = l2Limit;
    }

    /**
     * 获取指定会话的聊天消息列表
     * 
     * <p>
     * 按照 L3 → L2 → L1 的顺序组装消息：
     * </p>
     * <ol>
     * <li>注入用户全局偏好（作为 SystemMessage）</li>
     * <li>注入 L3 长期摘要（如果有）</li>
     * <li>注入 L2 中期摘要（如果有）</li>
     * <li>注入 L1 原始消息（从 Redis 读取）</li>
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
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("[Hierarchical Chat Memory Store] Retrieving memory for session: {}", sessionId);

        // 将动态上下文和持久化 system 指令合并为单条 SystemMessage，兼容只接受单 system 的模型后端
        ArrayList<String> systemSegments = new ArrayList<>();
        ArrayList<ChatMessage> otherMessages = new ArrayList<>();

        // 从 Redis 获取 L1 原始消息，支持多种数据格式的自动转换
        Object l1Data = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
        if (l1Data != null) {
            List<ChatMessage> l1Messages = deserializeChatMessages(l1Data);
            for (ChatMessage msg : l1Messages) {
                if (msg instanceof SystemMessage systemMessage) {
                    extractStoredSystemSegment(systemMessage).ifPresent(systemSegments::add);
                } else {
                    otherMessages.add(msg);
                }
            }
        }

        // 获取全局偏好组装后 注入系统提示词List
        injectUserPreferences(sessionId, systemSegments);
        // 获取L3摘要组装后 注入系统提示词List
        injectSummary(sessionId, "session:memory:l3:", "Long-range session summary", systemSegments);
        // 获取L2摘要组装后 注入系统提示词List
        injectSummary(sessionId, "session:memory:l2:", "Medium-range session summary", systemSegments);

        ArrayList<ChatMessage> messages = new ArrayList<>();
        // 委托 SystemPromptAssembler 将静态指令与动态上下文合并为单条 SystemMessage
        systemPromptAssembler.assemble(systemSegments).ifPresent(messages::add);
        messages.addAll(otherMessages);
        return messages;
    }

    /**
     * 更新指定会话的聊天消息列表（基于滑动窗口机制）
     * 
     * <p>
     * 执行以下操作：
     * </p>
     * <ol>
     * <li>过滤出真实的对话消息，排除内部合成的系统上下文中枢指令</li>
     * <li>检查活跃窗口大小：若非系统消息数超出 {@code l1Limit}，则触发滑动，驱逐（evict）最旧的消息</li>
     * <li>将滑动后剩余的、最新的 {@code l1Limit} 条消息覆盖存入 Redis L1 高速缓存</li>
     * <li>若发生了滑动，把遭驱逐的旧消息交由虚拟线程，异步触发 L2/L3 层级的滚动摘要与提炼</li>
     * </ol>
     * 
     * @param memoryId 内存标识符（即 session ID）
     * @param messages 当前会话已存在的全量消息列表（由前端与大模型生成追加组合而来）
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L1
     * @see MemoryStoreConstants#L1_CACHE_TTL_HOURS
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = memoryId.toString();

        // 1. 过滤掉由 SystemPromptAssembler 合成的 SystemMessage，只保留真实对话消息
        List<ChatMessage> pureMessages = messages.stream()
                .filter(msg -> !(msg instanceof SystemMessage sm && systemPromptAssembler.isSynthetic(sm)))
                .collect(Collectors.toList());

        log.info("[Hierarchical Chat Memory Store] Updating memory for session: {}. Pure messages count: {}", sessionId,
                pureMessages.size());

        List<ChatMessage> l1Messages = pureMessages;
        List<ChatMessage> evictedMessages = new ArrayList<>();

        // 2. Sliding Window Logic: evict oldest messages that exceed the L1 capacity
        if (pureMessages.size() > l1Limit) {
            int evictCount = pureMessages.size() - l1Limit;
            evictedMessages = pureMessages.subList(0, evictCount);
            l1Messages = pureMessages.subList(evictCount, pureMessages.size());
        }

        // 确保至少保存一条消息
        if (l1Messages.isEmpty() && !pureMessages.isEmpty()) {
            l1Messages = new ArrayList<>(pureMessages);
        }

        log.debug("[Hierarchical Chat Memory Store] Saving {} messages to Redis L1 cache", l1Messages.size());
        // 3. 将 L1 存入 Redis
        try {
            String l1Json = dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(l1Messages);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId, l1Json,
                    MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[Hierarchical Chat Memory Store] Failed to serialize recent messages to JSON: ", e);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId, l1Messages,
                    MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);
        }

        // 4. 触发异步滑动窗口摘要刷新
        if (!evictedMessages.isEmpty()) {
            final List<ChatMessage> finalEvicted = new ArrayList<>(evictedMessages);
            // 使用命名虚拟线程 + TtlRunnable，便于监控定位，同时保留请求上下文传播能力
            Runnable task = TtlRunnable.get(
                    () -> refreshSummariesSlidingWindow(sessionId, finalEvicted));
            Thread.ofVirtual()
                    .name("memory-summarizer[" + sessionId + "]")
                    .start(task);
        }
    }

    /**
     * 删除指定会话的所有内存数据
     * 
     * <p>
     * 清理 Redis 中的 L1、L2、L3 三级缓存
     * </p>
     * 
     * @param memoryId 内存标识符（即 session ID）
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L1
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = memoryId.toString();
        log.info("[Hierarchical Chat Memory Store] Deleting memory for session: {}", sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId);
        redisTemplate.delete(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId);
    }

    /**
     * 注入用户全局偏好设置
     * 
     * <p>
     * 从数据库加载用户的全局记忆/偏好，构造成 SystemMessage 注入到对话上下文中。
     * </p>
     * <p>
     * 这些偏好会永久影响 AI 的回复风格和内容。
     * </p>
     * 
     * @param sessionId 会话 ID
     * @param target    目标消息列表（用于添加 SystemMessage）
     * 
     * @see MemoryStoreConstants#GLOBAL_MEMORY_PREFIX
     * @see MemoryStoreConstants#GLOBAL_MEMORY_ITEM_PREFIX
     * @see MemoryStoreConstants#GLOBAL_MEMORY_KV_SEPARATOR
     */
    private void injectUserPreferences(String sessionId, List<String> target) {
        ChatSessionDO session = findSession(sessionId);
        if (session == null) {
            return;
        }

        // 偏好条目组装
        List<UserGlobalMemory> preferences = userGlobalMemoryMapper.selectList(
                new LambdaQueryWrapper<UserGlobalMemory>().eq(UserGlobalMemory::getUserId, session.getUserId()));
        if (!preferences.isEmpty()) {
            String globalMemStr = MemoryStoreConstants.GLOBAL_MEMORY_PREFIX +
                    preferences.stream()
                            .map(p -> MemoryStoreConstants.GLOBAL_MEMORY_ITEM_PREFIX +
                                    p.getPreferenceKey() +
                                    MemoryStoreConstants.GLOBAL_MEMORY_KV_SEPARATOR +
                                    p.getPreferenceValue())
                            .collect(Collectors.joining("\n"));
            target.add(globalMemStr);
        }
    }

    /**
     * 注入会话摘要
     * 
     * <p>
     * 从 Redis 读取指定前缀的摘要内容，构造成 SystemMessage 注入到对话上下文中。
     * </p>
     * 
     * @param sessionId 会话 ID
     * @param prefix    Redis 键前缀（如 "session:memory:l2:"）
     * @param title     摘要标题（用于构建 SystemMessage）
     * @param target    目标消息列表
     * 
     * @see MemoryStoreConstants#REDIS_PREFIX_L2
     * @see MemoryStoreConstants#REDIS_PREFIX_L3
     */
    private void injectSummary(String sessionId, String prefix, String title, List<String> target) {
        Object summary = redisTemplate.opsForValue().get(prefix + sessionId);
        if (summary instanceof String text && !text.isBlank()) {
            target.add(title + ":\n" + text);
        }
    }

    /**
     * 提取持久化的 SystemMessage 文本
     *
     * <p>
     * 从 Redis L1 缓存的历史消息中提取有效的系统指令片段，过滤掉由 {@link SystemPromptAssembler}
     * 合成的复合 SystemMessage，避免重复注入导致上下文膨胀。
     * </p>
     *
     * @param systemMessage 待检查的系统消息对象
     * @return 提取后的文本片段（空表示应忽略）
     *
     * @see SystemPromptAssembler#isSynthetic(SystemMessage)
     */
    private Optional<String> extractStoredSystemSegment(SystemMessage systemMessage) {
        String text = systemMessage.text();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        // 过滤掉由 SystemPromptAssembler 合成的消息，避免将其作为片段再次注入
        if (systemPromptAssembler.isSynthetic(systemMessage)) {
            return Optional.empty();
        }
        return Optional.of(text.trim());
    }

    /**
     * 基于滑动窗口的摘要刷新（在虚拟线程中执行）
     *
     * <p>
     * 核心业务流程：
     * </p>
     * <ol>
     * <li>读取现有 L2（中期存储）滚动摘要，并结合新踢出 L1 的消息生成新滚动摘要</li>
     * <li>统计 L2 已合并的消息数量。如果总数 > l2Limit，则触发 L3 升维摘要</li>
     * <li>将 L2 凝练至 L3，然后保留最新对话意图摘要，持久化状态</li>
     * </ol>
     *
     * @param sessionId       会话 ID
     * @param evictedMessages 该轮滑动窗口被强制踢出的消息列表
     */
    private void refreshSummariesSlidingWindow(String sessionId, List<ChatMessage> evictedMessages) {
        try {
            // 1. 获取现有 L2 滚动摘要
            Object l2Obj = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId);
            String currentL2 = l2Obj instanceof String ? (String) l2Obj : "";

            // 2. 将被剔除的消息滚入 L2
            String newL2Summary = summarizeRollingL2(currentL2, evictedMessages);
            redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L2 + sessionId, newL2Summary,
                    MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);

            // 3. 增加 L2 滑动计数（用于判定何时生成 L3）
            String countKey = "session:memory:l2_count:" + sessionId;
            Long count = redisTemplate.opsForValue().increment(countKey, evictedMessages.size());

            // 4. 判断是否触达 L3 获取条件
            String curL3 = null;
            Object l3Obj = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId);
            String currentL3 = l3Obj instanceof String ? (String) l3Obj : "";

            if (count != null && count >= l2Limit) {
                // 压缩长线意图，提取 Durable facts & entities 到 L3
                curL3 = distillL3(currentL3, newL2Summary);
                redisTemplate.opsForValue().set(MemoryStoreConstants.REDIS_PREFIX_L3 + sessionId, curL3,
                        MemoryStoreConstants.L1_CACHE_TTL_HOURS, TimeUnit.HOURS);

                // LLM 会把所有的事实转移到 L3。L2 的历史滚动量清盘重置。
                redisTemplate.delete(countKey);
            } else {
                curL3 = currentL3; // 保留现有的 L3
            }

            // 5. 持久化层级记录到 DB
            persistCompressionState(sessionId, newL2Summary, curL3);
        } catch (Exception exception) {
            BusinessException businessException = BusinessExceptionMapper.map(exception, ErrorCode.SYSTEM_ERROR);
            log.warn("[Hierarchical Chat Memory Store] Failed to refresh hierarchical sliding summaries for session {}: code={}, message={}",
                    sessionId, businessException.getCode(), businessException.getMessage(), exception);
        }
    }

    /**
     * 生成 L2 滚动叙事摘要
     *
     * <p>
     * 使用 LLM 将刚从 L1 滑出的旧消息无缝融合到当前的 L2 摘要中，形成连贯的中期上下文叙事。
     * </p>
     *
     * @param currentL2       当前的 L2 滚动摘要（由于新会话开启可能为空）
     * @param evictedMessages 此轮从 L1 活跃窗口中由于滑块越界、被“淘汰”出局的原始消息列表
     * @return 智能融合历史与新增信息生成的新 L2 滚动摘要
     *
     * @see MemoryStoreConstants#L2_SUMMARY_PROMPT
     */
    private String summarizeRollingL2(String currentL2, List<ChatMessage> evictedMessages) {
        if (evictedMessages == null || evictedMessages.isEmpty()) {
            return currentL2;
        }
        String transcript = evictedMessages.stream()
                .map(this::formatMessage)
                .collect(Collectors.joining("\n"));

        String prompt = "Please update the conversation summary seamlessly. Merge the current summary context with the newly evicted messages to form a unified, continuous narrative.\n\n";
        if (currentL2 != null && !currentL2.isBlank()) {
            prompt += "Current Medium-term Summary:\n" + currentL2 + "\n\n";
        }
        prompt += "Newly Evicted Messages to incorporate:\n" + transcript + "\n\n";
        prompt += "Instruction: " + MemoryStoreConstants.L2_SUMMARY_PROMPT;
        return chatLanguageModel.chat(prompt).trim();
    }

    /**
     * 提炼 L3 长期核心事实（知识大蒸馏）
     *
     * <p>
     * 当系统判断 L2 吸收的碎片化消息量过于冗长（达到提炼阈值）时触发本方法。利用 LLM 的总结能力
     * 从中期摘要中仅抽取最具耐久度的核心客观事实和核心实体，并排除日常闲聊与瞬时冗余。
     * </p>
     *
     * @param currentL3 现有的 L3 长期核心事实资料库描述
     * @param l2Summary 等待被“挤干水分”提纯压缩的当前 L2 滚动上下文
     * @return 提炼后生成的更高浓度维度、长期记忆 L3（此结果将替换或追加至以往核心知识体系中）
     *
     * @see MemoryStoreConstants#L3_SUMMARY_PROMPT
     */
    private String distillL3(String currentL3, String l2Summary) {
        String prompt = "Extract durable, long-term facts, core entities, and key global user decisions from the medium-term summary to build a persistent memory profile. Discard all transient or unresolved topic discussions.\n\n";
        if (currentL3 != null && !currentL3.isBlank()) {
            prompt += "Existing Durable Facts (L3):\n" + currentL3 + "\n\n";
        }
        prompt += "Medium-term Summary to Distill (L2):\n" + l2Summary + "\n\n";
        prompt += "Instruction: " + MemoryStoreConstants.L3_SUMMARY_PROMPT;
        return chatLanguageModel.chat(prompt).trim();
    }

    /**
     * 格式化单条消息
     * 
     * <p>
     * 将 ChatMessage 对象转换为可读的文本格式，用于 LLM 摘要生成。
     * </p>
     * <p>
     * 根据消息类型添加不同的前缀标识。
     * </p>
     * 
     * @param message 聊天消息对象
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
     * <p>
     * 为每条历史消息标记压缩级别并保存：
     * </p>
     * <ul>
     * <li><strong>L1</strong>：最近消息，无压缩，compressedContent = null</li>
     * <li><strong>L2</strong>：中期消息，使用 L2 摘要压缩</li>
     * <li><strong>L3</strong>：长期消息，使用 L3 摘要压缩</li>
     * </ul>
     * <p>
     * 同时更新会话表的 summary 字段（如果有 L3 摘要）。
     * </p>
     * 
     * @param sessionId 会话 ID
     * @param l2Summary L2 级摘要内容
     * @param l3Summary L3 级摘要内容（可为 null）
     * 
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L1
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L2
     * @see MemoryStoreConstants#COMPRESSION_LEVEL_L3
     */
    private void persistCompressionState(String sessionId, String l2Summary, String l3Summary) {
        List<ChatMessageDO> persistedMessages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getSessionId, sessionId)
                        .orderByAsc(ChatMessageDO::getCreatedAt));

        int total = persistedMessages.size();
        if (total == 0) {
            return;
        }

        int l1Start = Math.max(0, total - l1Limit);
        int l2Start = Math.max(0, total - l1Limit - l2Limit);

        // 批量归档更新 L2 中期摘要（包括刚刚从 L1 跌落的和原本就在 L2 的这批消息）
        if (l1Start > 0) {
            List<Long> l2Ids = persistedMessages.subList(l2Start, l1Start).stream()
                    .map(ChatMessageDO::getId)
                    .collect(Collectors.toList());
            if (!l2Ids.isEmpty()) {
                ChatMessageDO updateL2Template = new ChatMessageDO();
                updateL2Template.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L1); // Dummy reset
                updateL2Template.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L2);
                updateL2Template.setCompressedContent(l2Summary);
                chatMessageMapper.update(updateL2Template, new LambdaQueryWrapper<ChatMessageDO>().in(ChatMessageDO::getId, l2Ids));
            }
        }

        // 批量归档更新 L3 长期事实（包括刚刚升维降级的以及最古老的这批消息）
        if (l2Start > 0 && l3Summary != null && !l3Summary.isBlank()) {
            List<Long> l3Ids = persistedMessages.subList(0, l2Start).stream()
                    .map(ChatMessageDO::getId)
                    .collect(Collectors.toList());
            if (!l3Ids.isEmpty()) {
                ChatMessageDO updateL3Template = new ChatMessageDO();
                updateL3Template.setCompressionLevel(MemoryStoreConstants.COMPRESSION_LEVEL_L3);
                updateL3Template.setCompressedContent(l3Summary);
                chatMessageMapper.update(updateL3Template, new LambdaQueryWrapper<ChatMessageDO>().in(ChatMessageDO::getId, l3Ids));
            }
        }

        if (l3Summary != null && !l3Summary.isBlank()) {
            ChatSessionDO session = findSession(sessionId);
            if (session != null) {
                session.setSummary(l3Summary);
                chatSessionMapper.updateById(session);
            }
        }
    }

    private ChatSessionDO findSession(String sessionId) {
        return chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSessionDO>().eq(ChatSessionDO::getSessionId, sessionId));
    }

    /**
     * 智能反序列化聊天消息列表
     * 
     * <p>
     * 解决 Redis 序列化不一致问题的核心方法。支持三种数据源：
     * </p>
     * <ol>
     * <li><strong>List&lt;?&gt;</strong>：检查元素类型，直接返回或转换</li>
     * <li><strong>String（JSON）</strong>：使用 ObjectMapper 反序列化</li>
     * <li><strong>其他类型</strong>：记录警告并返回空列表</li>
     * </ol>
     * 
     * <p>
     * <strong>容错机制：</strong>
     * </p>
     * <ul>
     * <li>JSON 解析失败时返回空列表而非抛异常</li>
     * <li>未知类型优雅降级，不影响主流程</li>
     * <li>详细日志记录，便于问题排查</li>
     * <li>空列表或无效数据直接过滤</li>
     * </ul>
     * 
     * @param data Redis 中读取的原始数据
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
     * <p>
     * 支持多种输入类型的智能转换：
     * </p>
     * <ul>
     * <li><strong>ChatMessage 子类</strong>：直接返回原对象</li>
     * <li><strong>String</strong>：包装为 UserMessage（纯文本输入）</li>
     * <li><strong>Map<?,?></strong>：检查是否包含消息字段，有则转换</li>
     * <li><strong>其他类型</strong>：记录警告并返回 null</li>
     * </ul>
     * 
     * <p>
     * <strong>典型应用场景：</strong>
     * </p>
     * <ol>
     * <li>Redis 数据格式不一致时的兜底转换</li>
     * <li>历史数据迁移时的格式适配</li>
     * <li>第三方系统集成的数据兼容</li>
     * </ol>
     * 
     * @param item 待转换的对象
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
