package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.mq.DocumentMessageProducer;

/**
 * 文档业务逻辑层。
 * 负责从前端接收文件 → 存入 MinIO → 写入元数据到 MySQL → 触发 Kafka 管道。
 * 所有方法均为阻塞调用，调用方应在 boundedElastic 调度器上执行。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final MinioStorageService minioStorageService;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentMessageProducer documentMessageProducer;

    public DocumentService(MinioStorageService minioStorageService,
                           DocumentMetadataMapper documentMetadataMapper,
                           DocumentMessageProducer documentMessageProducer) {
        this.minioStorageService = minioStorageService;
        this.documentMetadataMapper = documentMetadataMapper;
        this.documentMessageProducer = documentMessageProducer;
    }

    @Transactional
    public DocumentMetadata uploadAndDispatch(String fileName, InputStream inputStream,
                                              long fileSize, String contentType, String tenantId) {
        String documentId = "doc-" + UUID.randomUUID();
        String extension = extractExtension(fileName);
        String objectName = documentId + "/" + fileName;

        log.info("Starting document upload pipeline: documentId={}, fileName={}, tenantId={}", documentId, fileName, tenantId);

        String minioUrl = minioStorageService.uploadFile(objectName, inputStream, fileSize, contentType);

        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setDocumentId(documentId);
        metadata.setFileName(fileName);
        metadata.setTenantId(tenantId);
        metadata.setMinioUrl(minioUrl);
        metadata.setFileExtension(extension);
        metadata.setStatus("UPLOADED_PENDING_PARSING");
        documentMetadataMapper.insert(metadata);

        log.info("Document metadata persisted to MySQL: documentId={}, status={}", documentId, metadata.getStatus());

        documentMessageProducer.sendDocParseRequest(documentId, minioUrl, extension);

        return metadata;
    }

    public DocumentMetadata getDocumentStatus(String documentId) {
        DocumentMetadata metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentMetadata>().eq("document_id", documentId)
        );

        if (metadata == null) {
            log.warn("Document not found: {}", documentId);
            throw new RuntimeException("Document not found: " + documentId);
        }

        return metadata;
    }

    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
