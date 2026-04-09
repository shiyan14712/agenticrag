package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.service.DocumentProcessingStateService;

/**
 * 文档异步处理状态的事务边界服务。
 *
 * <p>把短事务的状态迁移与长耗时 I/O 处理拆开，避免把 MinIO、Embedding、ES 等慢操作
 * 包在同一个数据库事务里。</p>
 */
@Service
public class DocumentProcessingStateServiceImpl implements DocumentProcessingStateService {

    private final DocumentMetadataMapper documentMetadataMapper;

    public DocumentProcessingStateServiceImpl(DocumentMetadataMapper documentMetadataMapper) {
        this.documentMetadataMapper = documentMetadataMapper;
    }

    @Transactional
    @Override
    public boolean transitionStatus(String documentId,
                                    DocumentProcessingStatus targetStatus,
                                    Collection<DocumentProcessingStatus> expectedCurrentStatuses) {
        if (!StringUtils.hasText(documentId) || targetStatus == null) {
            return false;
        }

        List<String> allowedStatuses = expectedCurrentStatuses == null
                ? List.of()
                : expectedCurrentStatuses.stream().map(DocumentProcessingStatus::value).toList();
        if (allowedStatuses.isEmpty()) {
            return false;
        }

        LambdaUpdateWrapper<DocumentDO> updateWrapper = new LambdaUpdateWrapper<DocumentDO>()
                .eq(DocumentDO::getDocumentId, documentId)
                .in(DocumentDO::getStatus, allowedStatuses)
                .set(DocumentDO::getStatus, targetStatus.value());
        return documentMetadataMapper.update(null, updateWrapper) > 0;
    }

    @Transactional
    @Override
    public void updateStatus(String documentId, DocumentProcessingStatus targetStatus) {
        if (!StringUtils.hasText(documentId) || targetStatus == null) {
            return;
        }
        LambdaUpdateWrapper<DocumentDO> updateWrapper = new LambdaUpdateWrapper<DocumentDO>()
                .eq(DocumentDO::getDocumentId, documentId)
                .set(DocumentDO::getStatus, targetStatus.value());
        documentMetadataMapper.update(null, updateWrapper);
    }

    @Override
    public String getCurrentStatus(String documentId) {
        DocumentDO metadata = documentMetadataMapper.selectOne(
                new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getDocumentId, documentId)
                        .select(DocumentDO::getStatus)
        );
        return metadata == null ? null : metadata.getStatus();
    }
}
