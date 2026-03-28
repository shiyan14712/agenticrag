package com.yoswell.agenticrag.core.agent.dto;

/**
 * 意图路由决策数据传输对象
 * 
 * <p>路由分类结果实体，基于 LangChain4j 结构化输出（JSON Schema）映射。</p>
 * 
 * <p>该 DTO 用于 IntentRouterAgent 对用户查询进行意图识别后的分类结果，
 * 决定后续处理流程：是直接回答、RAG 检索、还是调用特定工具。</p>
 * 
 * <p>参考架构文档：CLAUDE.md - Agent 编排与对话交互模块</p>
 * 
 * @param intent 意图类型标识，如 "DIRECT_ANSWER", "RAG_SEARCH", "TOOL_CALL" 等
 * @param confidence 置信度得分，范围通常为 0.0-1.0，表示模型对分类结果的把握程度
 * @author AgenticRAG
 * @since 2026-03-28
 */
public record IntentDecisionDTO(
    String intent,
    double confidence
) {}
