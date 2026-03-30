package com.yoswell.agenticrag.core.memory.constants;

/**
 * 分层聊天内存存储常量类
 * 
 * <p>定义 HierarchicalChatMemoryStore 中使用的各类常量，包括：</p>
 * - Redis 键前缀
 * - 摘要生成提示词
 * - 内存管理配置
 * 
 * @author AgenticRAG Team
 * @version 1.0
 * @since 2026-03-31
 */
public final class MemoryStoreConstants {

    /**
     * 私有构造函数，防止实例化
     */
    private MemoryStoreConstants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }

    // ==================== Redis 键前缀常量 ====================

    /**
     * L1 级内存（最近消息）Redis 键前缀
     * <p>存储最近的原始对话消息，保持最高精度</p>
     */
    public static final String REDIS_PREFIX_L1 = "session:memory:l1:";

    /**
     * L2 级内存（中期摘要）Redis 键前缀
     * <p>存储中期对话的压缩摘要，平衡精度与容量</p>
     */
    public static final String REDIS_PREFIX_L2 = "session:memory:l2:";

    /**
     * L3 级内存（长期摘要）Redis 键前缀
     * <p>存储长期对话的核心摘要，保留最关键信息</p>
     */
    public static final String REDIS_PREFIX_L3 = "session:memory:l3:";

    // ==================== 摘要生成提示词常量 ====================

    /**
     * L2 级摘要生成提示词（中文无 bullet 格式）
     * <p>用于生成中期对话摘要，关注用户意图和事实上下文</p>
     */
    public static final String L2_SUMMARY_PROMPT = "Summarize the dialogue in concise Chinese bullet-free prose, focusing on user intent and factual context.";

    /**
     * L3 级摘要生成提示词（中文核心事实压缩）
     * <p>用于生成长期对话摘要，仅保留持久性事实、决策和实体</p>
     */
    public static final String L3_SUMMARY_PROMPT = "Compress the dialogue into a very short Chinese summary of durable facts, decisions, and entities only.";

    // ==================== 内存管理配置常量 ====================

    /**
     * L1 缓存过期时间（小时）
     * <p>最近消息在 Redis 中的存活时间</p>
     */
    public static final long L1_CACHE_TTL_HOURS = 12L;

    /**
     * 全局内存提示前缀
     * <p>用于构建系统消息中的用户偏好提示</p>
     */
    public static final String GLOBAL_MEMORY_PREFIX = "Here are long-term facts/preferences you must remember about this user:\n";

    /**
     * 全局记忆项前缀符号
     * <p>每条全局记忆前的标记符号</p>
     */
    public static final String GLOBAL_MEMORY_ITEM_PREFIX = "- ";

    /**
     * 全局记忆键值分隔符
     * <p>记忆键和记忆值之间的分隔符</p>
     */
    public static final String GLOBAL_MEMORY_KV_SEPARATOR = ": ";

    // ==================== 消息格式化常量 ====================

    /**
     * 用户消息前缀
     */
    public static final String USER_MESSAGE_PREFIX = "user: ";

    /**
     * 助手消息前缀
     */
    public static final String ASSISTANT_MESSAGE_PREFIX = "assistant: ";

    /**
     * 工具消息前缀
     */
    public static final String TOOL_MESSAGE_PREFIX = "tool: ";

    /**
     * 系统消息前缀
     */
    public static final String SYSTEM_MESSAGE_PREFIX = "system: ";

    // ==================== 压缩级别常量 ====================

    /**
     * L1 压缩级别标识（无压缩）
     */
    public static final String COMPRESSION_LEVEL_L1 = "L1";

    /**
     * L2 压缩级别标识（中期压缩）
     */
    public static final String COMPRESSION_LEVEL_L2 = "L2";

    /**
     * L3 压缩级别标识（长期压缩）
     */
    public static final String COMPRESSION_LEVEL_L3 = "L3";
}
