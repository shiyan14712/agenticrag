package com.yoswell.agenticrag.core.memory.dto;

import java.util.List;

import com.yoswell.agenticrag.core.memory.model.MessageType;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;

/**
 * DTO used by memory inspection APIs to expose assembled LLM context.
 */
@Data
public class ChatMessageDTO {

    private MessageType type;
    private String text;
    private String contentType;
    private List<ToolCallDTO> toolCalls;
    private String toolCallId;
    private String toolName;
    private Boolean isError;

    public static ChatMessageDTO from(ChatMessage message) {
        if (message == null) {
            return null;
        }
        ChatMessageDTO dto = new ChatMessageDTO();
        switch (message) {
            case SystemMessage systemMessage -> {
                dto.setType(MessageType.SYSTEM);
                dto.setContentType("text");
                dto.setText(systemMessage.text());
            }
            case UserMessage userMessage -> {
                dto.setType(MessageType.USER);
                dto.setContentType("text");
                dto.setText(userMessage.singleText());
            }
            case AiMessage aiMessage -> {
                dto.setType(MessageType.AI);
                dto.setText(aiMessage.text());
                if (aiMessage.hasToolExecutionRequests()) {
                    dto.setContentType("tool_call");
                    dto.setToolCalls(aiMessage.toolExecutionRequests().stream()
                            .map(ToolCallDTO::from)
                            .toList());
                } else {
                    dto.setContentType("text");
                }
            }
            case ToolExecutionResultMessage toolMessage -> {
                dto.setType(MessageType.TOOL);
                dto.setContentType("tool_result");
                dto.setText(toolMessage.text());
                dto.setToolCallId(toolMessage.id());
                dto.setToolName(toolMessage.toolName());
                dto.setIsError(toolMessage.isError());
            }
            default -> {
                dto.setType(MessageType.UNKNOWN);
                dto.setContentType("unknown");
                dto.setText("");
            }
        }
        return dto;
    }

    public record ToolCallDTO(String id, String name, String arguments) {

        private static ToolCallDTO from(ToolExecutionRequest request) {
            return new ToolCallDTO(request.id(), request.name(), request.arguments());
        }
    }
}
