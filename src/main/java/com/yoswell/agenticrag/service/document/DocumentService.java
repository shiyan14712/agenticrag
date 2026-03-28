package com.yoswell.agenticrag.service.document;

import java.io.InputStream;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.entity.DocumentMetadata;
import com.yoswell.agenticrag.repository.DocumentMetadataRepository;
import com.yoswell.agenticrag.service.mq.DocumentMessageProducer;
import com.yoswell.agenticrag.service.storage.MinioService;

/**
 * 文档业务逻辑层。
 * 负责从前端接收文件 → 存入 MinIO → 写入元数据到 MySQL → 触发 Kafka 管道。
 * 所有方法均为阻塞调用，调用方应在 boundedElastic 调度器上执行。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final MinioService minioService;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final DocumentMessageProducer documentMessageProducer;

    public DocumentService(MinioService minioService,
                           DocumentMetadataRepository documentMetadataRepository,
                           DocumentMessageProducer documentMessageProducer) {
        this.minioService = minioService;
        this.documentMetadataRepository = documentMetadataRepository;
        this.documentMessageProducer = documentMessageProducer;
    }

    /**
     * 上传文件并启动管道：MinIO 存储 → MySQL 元数据 → Kafka 消息
     *
     * @param fileName    原始文件名
     * @param inputStream 文件输入流
     * @param fileSize    文件大小（字节）
     * @param contentType MIME 类型
     * @param tenantId    租户 ID
     * @return 文档元数据（含 documentId 和 MinIO URL）
     */
    @Transactional
    public DocumentMetadata uploadAndDispatch(String fileName, InputStream inputStream,
                                              long fileSize, String contentType, String tenantId) {
        String documentId = "doc-" + UUID.randomUUID();
        String extension = extractExtension(fileName);
        String objectName = documentId + "/" + fileName;

        log.info("Starting document upload pipeline: documentId={}, fileName={}, tenantId={}", documentId, fileName, tenantId);

        // 1. 上传到 MinIO
        String minioUrl = minioService.uploadFile(objectName, inputStream, fileSize, contentType);

        // 2. 写入 MySQL 元数据
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setDocumentId(documentId);
        metadata.setFileName(fileName);
        metadata.setTenantId(tenantId);
        metadata.setMinioUrl(minioUrl);
        metadata.setFileExtension(extension);
        metadata.setStatus("UPLOADED_PENDING_PARSING");
        documentMetadataRepository.insert(metadata);

        log.info("Document metadata persisted to MySQL: documentId={}, status={}", documentId, metadata.getStatus());

        // 3. 触发 Kafka 管道消息
        documentMessageProducer.sendDocParseRequest(documentId, minioUrl, extension);

        return metadata;
    }

    /**
     * 查询文档处理状态
     */
    public DocumentMetadata getDocumentStatus(String documentId) {
        DocumentMetadata metadata = documentMetadataRepository.selectOne(
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
