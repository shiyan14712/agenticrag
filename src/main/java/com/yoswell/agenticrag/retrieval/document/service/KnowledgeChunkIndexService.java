package com.yoswell.agenticrag.retrieval.document.service;

import java.util.List;

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.retrieval.document.dto.KnowledgeChunkDocumentDTO;

/**
 * 知识块索引服务接口。
 */
public interface KnowledgeChunkIndexService {

    void indexChunks(List<KnowledgeChunkDocumentDTO> chunks);

    void deleteByDocumentId(String documentId, String tenantId);

    List<RetrievedChunkDTO> searchByKeyword(String query, String tenantId, List<String> allowedRoles, int size);

    List<RetrievedChunkDTO> searchByVector(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size);
}
