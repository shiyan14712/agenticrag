package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    public Mono<DocumentMetadata> handleReactiveUpload(FilePart file, String tenantId) {
        return file.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .collectList()
                .flatMap(byteArrayList -> Mono.fromCallable(() -> {
                    int totalSize = byteArrayList.stream().mapToInt(b -> b.length).sum();
                    byte[] allBytes = new byte[totalSize];
                    int offset = 0;
                    for (byte[] chunk : byteArrayList) {
                        System.arraycopy(chunk, 0, allBytes, offset, chunk.length);
                        offset += chunk.length;
                    }

                    java.io.InputStream inputStream = new java.io.ByteArrayInputStream(allBytes);
                    String fileName = file.filename();
                    String contentType = file.headers().getContentType() != null
                            ? file.headers().getContentType().toString()
                            : "application/octet-stream";

                    DocumentMetadata metadata = uploadAndDispatch(
                            fileName, inputStream, totalSize, contentType, tenantId);

                    log.info("Document upload pipeline completed via reactive endpoint: documentId={}", metadata.getDocumentId());
                    return metadata;
                }).subscribeOn(Schedulers.boundedElastic()));
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

    public DocumentMetadata getDocumentStatus(String documentId, String tenantId) {
        DocumentMetadata metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentMetadata>()
                        .eq("document_id", documentId)
                        .eq("tenant_id", tenantId)
        );

        if (metadata == null) {
            log.warn("Document not found or access denied: {}", documentId);
            throw new RuntimeException("Document not found or access denied: " + documentId);
        }

        return metadata;
    }

    public java.util.Map<String, String> getDocumentStatusDetails(String documentId, String tenantId) {
        DocumentMetadata metadata = getDocumentStatus(documentId, tenantId);
        return java.util.Map.of(
                "documentId", metadata.getDocumentId(),
                "status", metadata.getStatus(),
                "fileName", metadata.getFileName() != null ? metadata.getFileName() : ""
        );
    }

    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
