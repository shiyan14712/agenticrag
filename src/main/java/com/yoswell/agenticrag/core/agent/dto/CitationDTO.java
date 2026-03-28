package com.yoswell.agenticrag.core.agent.dto;

/**
 * 引用溯源数据传输对象
 * 
 * <p>企业级 RAG UI Widget 组件模型，用于向前端推送单条可追溯的引用片段。</p>
 * 
 * <p>该 DTO 是 SSE（Server-Sent Events）协议中 {@code event: citations} 事件的核心数据结构，
 * 前端据此渲染富文本引用卡片，使用户能够追溯大模型回答的知识来源。</p>
 * 
 * <p>参考架构文档：CLAUDE.md - Agent 编排与对话交互模块</p>
 * 
 * @param docId 业务文档 ID，对应 document_metadata.document_id
 * @param docName 原始文件名，用于前端展示
 * @param chunkId 知识块 ID，ElasticSearch 中的 Chunk 唯一标识
 * @param confidenceScore 置信度得分，Reranker 重排序后的最终评分
 * @author AgenticRAG
 * @since 2026-03-28
 */
public record CitationDTO(
    String docId,
    String docName,
    String chunkId,
    double confidenceScore
) {}
