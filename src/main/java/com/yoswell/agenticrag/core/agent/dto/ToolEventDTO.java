package com.yoswell.agenticrag.core.agent.dto;

/**
 * 工具执行事件的 JSON 载荷（前后端 SSE 契约）
 *
 * <p>用于 {@code event: tool_start} 和 {@code event: tool_result} 两个 SSE 事件。
 * 前端据此渲染加载动画（tool_start）或完成卡片（tool_result）。</p>
 *
 * @param tool          工具名，如 "search_enterprise_knowledge"、"save_user_preference"
 * @param status        执行状态："executing" | "completed" | "failed"
 * @param argsSummary   参数摘要（tool_start 阶段传入，用于前端展示如"正在检索：虚拟线程调度机制"）
 * @param resultSummary 结果摘要（tool_result 阶段传入，用于前端展示如"检索到 5 条知识片段"）
 * @param durationMs    执行耗时（可选，仅 tool_result 阶段填充）
 */
public record ToolEventDTO(
    String tool,
    String status,
    String argsSummary,
    String resultSummary,
    long durationMs
) {
    /** tool_start 工厂方法：工具即将执行 */
    public static ToolEventDTO executing(String toolName, String argsSummary) {
        return new ToolEventDTO(toolName, "executing", argsSummary, null, 0);
    }

    /** tool_result 工厂方法：工具执行完成 */
    public static ToolEventDTO completed(String toolName, String resultSummary, long durationMs) {
        return new ToolEventDTO(toolName, "completed", null, resultSummary, durationMs);
    }

    /** tool_result 工厂方法：工具执行失败 */
    public static ToolEventDTO failed(String toolName, String errorMessage) {
        return new ToolEventDTO(toolName, "failed", null, errorMessage, 0);
    }
}
