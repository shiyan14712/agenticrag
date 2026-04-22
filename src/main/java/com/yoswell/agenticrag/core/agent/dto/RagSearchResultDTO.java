package com.yoswell.agenticrag.core.agent.dto;

import java.util.List;

/**
 * RAG 检索结果数据传输对象
 * 
 * <p>封装企业知识库检索后的完整结果集，包含观察摘要、召回的知识块列表和引用溯源信息。</p>
 * 
 * <p>该 DTO 是 RAG Tool 执行混合检索（Hybrid Search）后的标准化输出，
 * 供 ChatOrchestrator 在 SSE 流中推送 {@code event: citations} 事件时使用。</p>
 * 
 * <p>参考架构文档：CLAUDE.md - RAG 核心引擎模块</p>
 * 
 * @param observation 检索观察摘要，Tool 返回给大模型的 Observation 文本描述
 * @param retrievedChunks 召回的知识块列表，包含原始内容和元数据信息
 * @param citations 引用溯源列表，用于前端渲染可追溯的引用卡片
 * @author AgenticRAG
 * @since 2026-03-28
 */
public record RagSearchResultDTO(
        String observation,
        List<RetrievedChunkDTO> retrievedChunks,
        List<CitationDTO> citations
) {
}
