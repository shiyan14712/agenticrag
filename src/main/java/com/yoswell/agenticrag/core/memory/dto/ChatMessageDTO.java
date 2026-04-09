package com.yoswell.agenticrag.core.memory.dto;

import com.yoswell.agenticrag.core.memory.model.MessageType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;

/**
 * 聊天消息数据传输对象
 * 
 * <p>用于将 LangChain4j 框架内部的 {@link ChatMessage} 对象转换为前端可识别的简化 DTO，
 * 屏蔽底层技术细节，提供统一的类型标识和文本内容。</p>
 * 
 * <p><strong>典型应用场景：</strong></p>
 * <ul>
 *     <li>会话记忆查询接口：将 L1/L2/L3 层级的记忆数据转换为前端展示格式</li>
 *     <li>SSE 流式对话上下文推送：组装完整对话历史时进行格式转换</li>
 * </ul>
 * 
 * @see com.yoswell.agenticrag.core.memory.service.SessionMemoryService#getSessionMemoryLayers(String)
 * @see com.yoswell.agenticrag.core.memory.service.SessionMemoryService#getAssembledContext(String)
 */
@Data
public class ChatMessageDTO {

    /**
     * 消息类型标识
     */
    private MessageType type;
    
    /**
     * 消息文本内容
     * <p>从不同类型的 ChatMessage 中提取的核心文本信息</p>
     */
    private String text;

    /**
     * 从 LangChain4j ChatMessage 转换为 DTO 对象的工厂方法。
     * 
     * <p><strong>转换规则：</strong></p>
     * <ul>
     *     <li>{@link SystemMessage} → type={@link MessageType#SYSTEM}, text=系统指令内容</li>
     *     <li>{@link UserMessage} → type={@link MessageType#USER}, text=用户输入文本</li>
     *     <li>{@link AiMessage} → type={@link MessageType#AI}, text=AI 生成的回复</li>
     *     <li>{@link ToolExecutionResultMessage} → type={@link MessageType#TOOL}, text=工具执行结果</li>
     *     <li>其他类型 → type={@link MessageType#UNKNOWN}, text=""（空字符串兜底）</li>
     * </ul>
     * 
     * <p><strong>调用链路：</strong></p>
     * <ol>
     *     <li>Controller: {@code MemoryQueryController.getSessionMemoryLayers()} / {@code getAssembledMemoryContext()}</li>
     *     <li>Service: {@code SessionMemoryServiceImpl.getSessionMemoryLayers()} / {@code getAssembledContext()}</li>
     *     <li>Stream: {@code l1Messages.stream().map(ChatMessageDTO::from).collect(Collectors.toList())}</li>
     * </ol>
     * 
     * <p><strong>设计意图：</strong></p>
     * <ul>
     *     <li>解耦 LangChain4j 框架依赖，避免将内部模型直接暴露给前端</li>
     *     <li>统一不同消息类型的字段访问方式（singleText()、text() 等差异被屏蔽）</li>
     *     <li>支持 null 安全转换，空指针时返回 null 而非抛出异常</li>
     * </ul>
     * 
     * @param message LangChain4j 聊天消息对象（可为 null）
     * @return 转换后的 DTO 对象，若输入为 null 则返回 null
     */
    public static ChatMessageDTO from(ChatMessage message) {
        if (message == null) {
            return null;
        }
        ChatMessageDTO dto = new ChatMessageDTO();
        switch (message) {
            case SystemMessage systemMessage -> {
                dto.setType(MessageType.SYSTEM);
                dto.setText(systemMessage.text());
            }
            case UserMessage userMessage -> {
                dto.setType(MessageType.USER);
                dto.setText(userMessage.singleText());
            }
            case AiMessage aiMessage -> {
                dto.setType(MessageType.AI);
                dto.setText(aiMessage.text());
            }
            case ToolExecutionResultMessage toolMessage -> {
                dto.setType(MessageType.TOOL);
                dto.setText(toolMessage.text());
            }
            default -> {
                dto.setType(MessageType.UNKNOWN);
                dto.setText("");
            }
        }
        return dto;
    }
}
