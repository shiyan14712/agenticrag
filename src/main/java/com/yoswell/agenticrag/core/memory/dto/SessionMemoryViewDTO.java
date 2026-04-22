package com.yoswell.agenticrag.core.memory.dto;

import java.util.List;

import lombok.Data;

@Data
public class SessionMemoryViewDTO {
    private String l3Summary;
    private String l2Summary;
    private List<ChatMessageDTO> l1Messages;
}
