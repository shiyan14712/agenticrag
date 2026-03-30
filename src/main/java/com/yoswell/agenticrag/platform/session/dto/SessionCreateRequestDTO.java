package com.yoswell.agenticrag.platform.session.dto;

import lombok.Data;

/**
 * 会话创建请求 DTO
 * <p>
 * 用于客户端发起新建会话请求时的数据传输对象。对应数据库表 {@code chat_session} 的创建操作。
 * 该 DTO 封装了用户初始化对话会话时可配置的参数，支持绑定特定 AI 模型。
 * </p>
 *
 * @see com.yoswell.agenticrag.platform.session.entity.ChatSession
 * @since 2026-03-30
 */
@Data
public class SessionCreateRequestDTO {

    /**
     * 绑定的 AI 模型标识
     * <p>
     * 可选字段。用于指定该会话使用的 LLM 模型 ID（如："gpt-4", "qwen-max" 等）。
     * 若不传，系统将使用默认配置模型。
     * </p>
     * <p>
     * 对应数据库字段：{@code chat_session.model_id}
     * </p>
     */
    private String modelId;
}
