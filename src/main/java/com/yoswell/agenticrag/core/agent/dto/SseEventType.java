package com.yoswell.agenticrag.core.agent.dto;

/**
 * SSE 事件类型枚举
 *
 * <p>统一管理后端向前端 SSE 通道推送的所有事件名称常量。
 * 前端应根据 {@code event.type} 分发渲染对应的 UI 组件。</p>
 *
 * <p>事件时间线（典型 ReAct 循环）：</p>
 * <pre>
 *   User Query →
 *     [thinking]    CoT 推理过程（流式）
 *     [tool_start]  工具开始执行
 *     [tool_result] 工具执行完成
 *     [message]     最终回答 token 流
 *     [citations]   引用卡片
 *     [done]        循环结束
 * </pre>
 */
public enum SseEventType {

    /** LLM 的思考/推理过程（CoT），流式推送。前端渲染为打字机动画 + "正在思考..." 标签 */
    THINKING("thinking"),

    /** Tool 即将开始执行。前端渲染为加载动画 + 工具名标签（如"正在检索知识库..."） */
    TOOL_START("tool_start"),

    /** Tool 执行完成。前端渲染为完成动画 + 结果摘要卡片 */
    TOOL_RESULT("tool_result"),

    /** 最终回答的 token 流式推送。前端渲染为 Markdown 打字机对话 */
    MESSAGE("message"),

    /** 引用溯源卡片。前端渲染为富文本引用卡片 */
    CITATIONS("citations"),

    /** 整个 ReAct 循环结束信号 */
    DONE("done"),

    /** 错误事件 */
    ERROR("error");

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
