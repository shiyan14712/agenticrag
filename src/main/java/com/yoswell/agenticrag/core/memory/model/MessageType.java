package com.yoswell.agenticrag.core.memory.model;

/**
 * 聊天消息类型枚举
 * 
 * <p>定义 LangChain4j ChatMessage 转换为 DTO 时的消息类型标识，
 * 用于区分不同角色的对话消息。</p>
 * 
 * <p><strong>使用场景：</strong></p>
 * <ul>
 *     <li>会话记忆分层展示（L1/L2/L3）</li>
 *     <li>SSE 流式对话上下文推送</li>
 *     <li>前端消息气泡渲染（用户/AI/系统/工具）</li>
 * </ul>
 * 
 * @author AgenticRAG Team
 * @since 2026-04-09
 */
public enum MessageType {
    
    /**
     * 系统指令消息
     * <p>用于设定 AI 助手的行为规范、角色定位和全局约束</p>
     */
    SYSTEM,
    
    /**
     * 用户输入消息
     * <p>用户在对话中发送的提问或指令</p>
     */
    USER,
    
    /**
     * AI 助手回复消息
     * <p>大语言模型生成的回答内容</p>
     */
    AI,
    
    /**
     * 工具调用结果消息
     * <p>RAG 检索、知识库查询等工具的执行结果</p>
     */
    TOOL,
    
    /**
     * 未知类型消息
     * <p>兜底类型，用于处理未识别的消息格式</p>
     */
    UNKNOWN
}
