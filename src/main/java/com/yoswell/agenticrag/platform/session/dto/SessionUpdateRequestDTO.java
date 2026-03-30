package com.yoswell.agenticrag.platform.session.dto;

import lombok.Data;

/**
 * 会话更新请求 DTO
 * <p>
 * 用于客户端发起会话属性变更请求时的数据传输对象。支持对已有 {@code chat_session} 记录进行部分字段更新。
 * 主要应用于以下场景：
 * </p>
 * <ul>
 *     <li>修改会话标题（由 LLM 自动生成或用户自定义）</li>
 *     <li>设置会话置顶状态（便于重要对话快速访问）</li>
 *     <li>未来可扩展：归档、删除等操作</li>
 * </ul>
 *
 * @see com.yoswell.agenticrag.platform.session.entity.ChatSession
 * @since 2026-03-30
 */
@Data
public class SessionUpdateRequestDTO {

    /**
     * 会话标题
     * <p>
     * 可选字段。用于更新会话的显示标题，通常在前几轮对话后由 LLM 自动总结生成，
     * 也允许用户手动修改为自定义名称（如"Q1 财报分析"、"系统架构设计讨论"）。
     * </p>
     * <p>
     * 对应数据库字段：{@code chat_session.title}
     * </p>
     */
    private String title;

    /**
     * 是否置顶
     * <p>
     * 可选字段。用于标记该会话是否需要置顶显示。置顶的会话会在会话列表中优先展示，
     * 方便用户快速访问重要对话（如正在进行的核心项目、高频使用的知识库问答）。
     * </p>
     * <p>
     * 对应数据库字段：{@code chat_session.pinned}
     * </p>
     */
    private Boolean pinned;
}
