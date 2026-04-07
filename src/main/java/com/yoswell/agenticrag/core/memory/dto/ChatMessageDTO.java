package com.yoswell.agenticrag.core.memory.dto;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Data;

@Data
public class ChatMessageDTO {
    private String type;
    private String text;

    public static ChatMessageDTO from(ChatMessage message) {
        if (message == null) {
            return null;
        }
        ChatMessageDTO dto = new ChatMessageDTO();
        switch (message) {
            case SystemMessage systemMessage -> {
                dto.setType("SYSTEM");
                dto.setText(systemMessage.text());
            }
            case UserMessage userMessage -> {
                dto.setType("USER");
                dto.setText(userMessage.singleText());
            }
            case AiMessage aiMessage -> {
                dto.setType("AI");
                dto.setText(aiMessage.text());
            }
            case ToolExecutionResultMessage toolMessage -> {
                dto.setType("TOOL");
                dto.setText(toolMessage.text());
            }
            default -> {
                dto.setType("UNKNOWN");
                dto.setText("");
            }
        }
        return dto;
    }
}
