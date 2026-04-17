package com.yoswell.agenticrag.retrieval.document.service;

import java.util.List;

import com.yoswell.agenticrag.retrieval.document.entity.KnowledgeChunkDocumentDO;

/**
 * 知识块写入服务接口
 *
 * <p>负责向量化产物的 ES 写入（批量 index）和按文档 ID 删除（delete-by-query），
 * 与查询路径解耦，便于后续独立演进写入策略（如批量提交、异步写入等）</p>
 */
public interface KnowledgeChunkWriteService {

    /**
     * 批量写入知识块到 Elasticsearch
     *
     * @param chunks 需要写入的知识块集合
     */
    void indexChunks(List<KnowledgeChunkDocumentDO> chunks);

    /**
     * 根据文档 ID 删除该文档在索引中的所有 chunk
     *
     * @param documentId 文档业务 ID
     * @param tenantId   所属租户 ID（作为安全过滤条件）
     */
    void deleteByDocumentId(String documentId, String tenantId);
}
