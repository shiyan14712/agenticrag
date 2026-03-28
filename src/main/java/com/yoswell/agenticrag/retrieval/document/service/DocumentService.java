package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.mq.DocumentMessageProducer;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档上传链路的业务编排服务。
 *
 * <p>它负责把一次上传请求拆成几个明确步骤：写入 MinIO、写入元数据表、
 * 再向异步处理队列发出后续任务。</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String DEFAULT_KB_ID = "default-kb";
    private static final List<String> DEFAULT_ALLOWED_ROLES = List.of("ROLE_USER");

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

    /**
     * 以 WebFlux 方式接收上传文件，并把阻塞 I/O 转移到 {@code boundedElastic} 线程池。
     *
     * @param file 上传文件
     * @param tenantId 当前租户 ID
     * @return 落库后的文档元数据
     */
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

                    InputStream inputStream = new java.io.ByteArrayInputStream(allBytes);
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

    /**
     * 执行一次完整的“上传并投递后续任务”事务。
     *
     * @param fileName 原始文件名
     * @param inputStream 文件输入流
     * @param fileSize 文件大小
     * @param contentType MIME 类型
     * @param tenantId 当前租户 ID
     * @return 已持久化的文档元数据
     */
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
        metadata.setKbId(DEFAULT_KB_ID);
        metadata.setMinioUrl(minioUrl);
        metadata.setFileExtension(extension);
        metadata.setAllowedRoles(String.join(",", DEFAULT_ALLOWED_ROLES));
        metadata.setStatus(DocumentProcessingStatus.UPLOADED.value());
        documentMetadataMapper.insert(metadata);

        log.info("Document metadata persisted to MySQL: documentId={}, status={}", documentId, metadata.getStatus());

        documentMessageProducer.sendDocParseRequest(new DocumentParseRequestDTO(
                documentId,
                tenantId,
                metadata.getKbId(),
                fileName,
                minioUrl,
                extension,
                DEFAULT_ALLOWED_ROLES,
                System.currentTimeMillis()
        ));

        return metadata;
    }

    /**
     * 查询指定文档在当前租户下的完整元数据。
     *
     * @param documentId 文档业务 ID
     * @param tenantId 当前租户 ID
     * @return 文档元数据
     */
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

    /**
     * 返回给前端展示用的轻量状态摘要。
     *
     * @param documentId 文档业务 ID
     * @param tenantId 当前租户 ID
     * @return 只包含关键状态字段的 map
     */
    public java.util.Map<String, String> getDocumentStatusDetails(String documentId, String tenantId) {
        DocumentMetadata metadata = getDocumentStatus(documentId, tenantId);
        return java.util.Map.of(
                "documentId", metadata.getDocumentId(),
                "status", metadata.getStatus(),
                "fileName", metadata.getFileName() != null ? metadata.getFileName() : ""
        );
    }

    /**
     * 获取指定租户的所有文档列表。
     *
     * @param tenantId 当前租户 ID
     * @return 文档 DTO 列表
     */
    public List<DocumentDTO> getUserDocuments(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be null or empty");
        }

        List<DocumentMetadata> metadataList = documentMetadataMapper.selectList(
                new QueryWrapper<DocumentMetadata>()
                        .eq("tenant_id", tenantId)
                        .orderByDesc("created_at")
        );

        return metadataList.stream()
                .map(meta -> new DocumentDTO(
                        meta.getDocumentId(),
                        meta.getFileName(),
                        meta.getFileExtension(),
                        meta.getStatus(),
                        meta.getCreatedAt(),
                        meta.getUpdatedAt()
                ))
                .toList();
    }

    /**
     * 删除指定文档（包含元数据和 MinIO 文件）。带有粗粒度租户隔离校验。
     *
     * @param documentId 文档业务 ID
     * @param tenantId   当前租户 ID
     */
    @Transactional
    public void deleteDocumentById(String documentId, String tenantId) {
        if (documentId == null || tenantId == null) {
            throw new IllegalArgumentException("Document ID and Tenant ID must be provided");
        }

        // 1. 查询文档确认是否存在，并强校验 tenantId 防止越权
        DocumentMetadata metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentMetadata>()
                        .eq("document_id", documentId)
                        .eq("tenant_id", tenantId)
        );

        if (metadata == null) {
            log.warn("[Document Service] Document not found or access denied for deletion. documentId: {}, tenantId: {}", documentId, tenantId);
            throw new RuntimeException("Document not found or access denied");
        }

        // 2. 数据库删除元数据
        documentMetadataMapper.deleteById(metadata.getId());
        log.info("[Document Service] SUCCESS: Document metadata deleted. documentId: {}", documentId);

        // 3. 异步删除 MinIO 上的文件。如果 URL 是合法的 objectName 形式，则可以通过存储服务清理。
        if (metadata.getMinioUrl() != null && !metadata.getMinioUrl().isEmpty()) {
            try {
                minioStorageService.deleteFile(metadata.getMinioUrl());
            } catch (Exception e) {
                // 这里只记录日志，不让异常打断事务，保证数据库的先删除
                log.error("[Document Service] Failed to delete document from MinIO. URL: {}", metadata.getMinioUrl(), e);
            }
        }
        
        // 4. 异步向 Kafka 发送文档已被删除的消息，供 ES 等下游组件完成向量删除
        documentMessageProducer.sendDocumentDeletedRequest(new DocumentDeleteRequestDTO(
                documentId,
                tenantId,
                System.currentTimeMillis()
        ));
    }

    /**
     * 从文件名提取扩展名，未识别时回退为 {@code unknown}。
     *
     * @param fileName 原始文件名
     * @return 归一化后的扩展名
     */
    private String extractExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
