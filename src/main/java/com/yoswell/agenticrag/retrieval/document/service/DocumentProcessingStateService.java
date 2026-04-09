package com.yoswell.agenticrag.retrieval.document.service;

import java.util.Collection;

import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;

/**
 * 文档处理状态服务接口
 */
public interface DocumentProcessingStateService {

    boolean transitionStatus(String documentId,
                             DocumentProcessingStatus targetStatus,
                             Collection<DocumentProcessingStatus> expectedCurrentStatuses);

    void updateStatus(String documentId, DocumentProcessingStatus targetStatus);

    String getCurrentStatus(String documentId);
}
