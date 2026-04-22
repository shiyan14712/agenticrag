package com.yoswell.agenticrag.retrieval.document.service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.model.DocumentVectorizationExecutionResult;

/**
 * 文档向量化服务接口
 */
public interface DocumentVectorizationService {

    DocumentVectorizationExecutionResult vectorize(DocumentVectorizeRequestDTO request);

    void markFailed(String documentId);
}
