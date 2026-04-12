package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.common.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentKafkaTopic;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.DocumentAsyncTaskDO;
import com.yoswell.agenticrag.retrieval.document.reliability.model.DocumentAsyncTaskType;
import com.yoswell.agenticrag.retrieval.document.reliability.model.MessageOutboxEventType;
import com.yoswell.agenticrag.retrieval.document.reliability.service.DocumentAsyncTaskService;
import com.yoswell.agenticrag.retrieval.document.reliability.service.DocumentOutboxService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentService;
import com.yoswell.agenticrag.retrieval.document.service.MinioStorageService;

import lombok.RequiredArgsConstructor;

/**
 * 文档上传链路的业务编排服务
 *
 * <p>它负责把一次上传请求拆成几个明确步骤：写入 MinIO、写入元数据表、
 * 再向异步处理队列发出后续任务</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final String DEFAULT_KB_ID = "default-kb";
    private static final List<String> DEFAULT_ALLOWED_ROLES = List.of("ROLE_USER");

    private final MinioStorageService minioStorageService;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentOutboxService documentOutboxService;
    private final DocumentAsyncTaskService documentAsyncTaskService;
    private final DocumentKafkaProperties kafkaProperties;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    /**
     * 同步接收上传文件并执行上传编排
     *
     * @param file 上传文件
     * @param tenantId 当前租户 ID
     * @return 落库后的文档元数据
     */
    @Override
    public DocumentDO handleUpload(MultipartFile file, String tenantId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            fileName = "unknown";
        }
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        log.info("[Upload Pipeline][RECEIVE] 开始处理上传文件: tenantId={}, fileName={}, size={} bytes, contentType={}",
                tenantId, fileName, file.getSize(), contentType);

        try (InputStream inputStream = file.getInputStream()) {
            DocumentDO metadata = uploadAndDispatch(fileName, inputStream, file.getSize(), contentType, tenantId);
            log.info("[Upload Pipeline][DONE] 上传链路完成: documentId={}, tenantId={}, finalStatus={}",
                    metadata.getDocumentId(), tenantId, metadata.getStatus());
            return metadata;
        } catch (Exception e) {
            log.error("[Upload Pipeline][FAILED] 上传链路执行失败: tenantId={}, fileName={}", tenantId, fileName, e);
            throw new RuntimeException("Failed to process document upload", e);
        }
    }

    /**
     * 执行一次完整的“上传并投递后续任务”事务
     *
     * @param fileName 原始文件名
     * @param inputStream 文件输入流
     * @param fileSize 文件大小
     * @param contentType MIME 类型
     * @param tenantId 当前租户 ID
     * @return 已持久化的文档元数据
     */
    @Transactional
    @Override
    public DocumentDO uploadAndDispatch(String fileName, InputStream inputStream,
                                        long fileSize, String contentType, String tenantId) {
        // ① 保存文档元数据到 document 表
        String documentId = "doc-" + UUID.randomUUID();
        String extension = extractExtension(fileName);
        String objectName = documentId + "/" + fileName;

        log.info("[Upload Pipeline][MINIO] 准备上传到 MinIO: documentId={}, objectName={}, tenantId={}",
            documentId, objectName, tenantId);

        String minioUrl = minioStorageService.uploadFile(objectName, inputStream, fileSize, contentType);
        log.info("[Upload Pipeline][MINIO] MinIO 上传完成: documentId={}, minioUrl={}", documentId, minioUrl);

        DocumentDO metadata = new DocumentDO();
        metadata.setDocumentId(documentId);
        metadata.setFileName(fileName);
        metadata.setTenantId(tenantId);
        metadata.setKbId(DEFAULT_KB_ID);
        metadata.setMinioUrl(minioUrl);
        metadata.setFileExtension(extension);
        metadata.setAllowedRoles(String.join(",", DEFAULT_ALLOWED_ROLES));
        metadata.setStatus(DocumentProcessingStatus.UPLOADED);

        int rowsAffected = documentMetadataMapper.insert(metadata);
        if (rowsAffected != 1) {
            throw new RuntimeException("Failed to insert document metadata");
        }

        log.info("[Upload Pipeline][METADATA] 元数据落库完成: documentId={}, tenantId={}, status={}, extension={}",
            documentId, tenantId, metadata.getStatus(), extension);

        // ② 创建异步任务记录
        DocumentAsyncTaskDO parseTask = documentAsyncTaskService.createTask(
                documentId,
                tenantId,
                DocumentAsyncTaskType.DOCUMENT_PARSE,
                DocumentKafkaTopic.PARSE_REQUEST,
                documentId
        );

        // ③ 写入 Outbox 表（与①在同一事务中）
        String messageId = "msg-" + UUID.randomUUID();
        documentOutboxService.enqueue(
                "DOCUMENT",
                documentId,
                parseTask.getTaskId(),
                MessageOutboxEventType.DOCUMENT_PARSE_REQUEST,
                DocumentKafkaTopic.PARSE_REQUEST,
                documentId,
                new DocumentParseRequestDTO(
                        documentId,
                        tenantId,
                        metadata.getKbId(),
                        fileName,
                        minioUrl,
                        extension,
                        DEFAULT_ALLOWED_ROLES,
                        parseTask.getTaskId(),
                        messageId,
                        System.currentTimeMillis()
                ));

        log.info("[Upload Pipeline][OUTBOX] 已登记解析任务: topic={}, documentId={}, tenantId={}, taskId={}, messageId={}",
            kafkaProperties.getTopics().resolve(DocumentKafkaTopic.PARSE_REQUEST),
            documentId,
            tenantId,
            parseTask.getTaskId(),
            messageId);

        return metadata;
    }

    /**
     * 查询指定文档在当前租户下的完整元数据
     *
     * @param documentId 文档业务 ID
     * @param tenantId 当前租户 ID
     * @return 文档元数据
     */
    @Override
    public DocumentDO getDocumentStatus(String documentId, String tenantId) {
        LambdaQueryWrapper<DocumentDO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DocumentDO::getDocumentId, documentId)
                .eq(DocumentDO::getTenantId, tenantId);
        DocumentDO metadata = documentMetadataMapper.selectOne(queryWrapper);

        if (metadata == null) {
            log.warn("Document not found or access denied: {}", documentId);
            throw new RuntimeException("Document not found or access denied: " + documentId);
        }

        return metadata;
    }

    /**
     * 返回给前端展示用的轻量状态摘要
     *
     * @param documentId 文档业务 ID
     * @param tenantId 当前租户 ID
     * @return 只包含关键状态字段的 map
     */
    @Override
    public java.util.Map<String, String> getDocumentStatusDetails(String documentId, String tenantId) {
        String cacheKey = "doc:status:" + documentId + ":" + tenantId;
        
        try {
            Object cachedStatus = redisTemplate.opsForValue().get(cacheKey);
            if (cachedStatus instanceof java.util.Map) {
                return (java.util.Map<String, String>) cachedStatus;
            }
        } catch (Exception e) {
            log.warn("[Document Service] Failed to get document status from Redis: {}", e.getMessage());
        }

        DocumentDO metadata = getDocumentStatus(documentId, tenantId);
        java.util.Map<String, String> statusMap = java.util.Map.of(
                "documentId", metadata.getDocumentId(),
                "status", metadata.getStatus().value(),
                "fileName", metadata.getFileName() != null ? metadata.getFileName() : ""
        );
        
        try {
            // 动态设置缓存时间：终态缓存较长，进行中状态缩短为 2 秒以保证前端轮询能较快拿到最新状态
            long cacheSeconds = 2;
            DocumentProcessingStatus status = metadata.getStatus();
            if (status == DocumentProcessingStatus.VECTORIZED || status == DocumentProcessingStatus.FAILED) {
                cacheSeconds = 60;
            }
            redisTemplate.opsForValue().set(cacheKey, statusMap, java.time.Duration.ofSeconds(cacheSeconds));
        } catch (Exception e) {
            log.warn("[Document Service] Failed to cache document status to Redis: {}", e.getMessage());
        }

        return statusMap;
    }

    /**
     * 获取指定租户的所有文档列表
     *
     * @param tenantId 当前租户 ID
     * @return 文档 DTO 列表
     */
    @Override
    public List<DocumentDTO> getUserDocuments(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID cannot be null or empty");
        }

        List<DocumentDO> metadataList = documentMetadataMapper.selectList(
                new QueryWrapper<DocumentDO>()
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
     * 删除指定文档（包含元数据和 MinIO 文件）带有粗粒度租户隔离校验
     *
     * @param documentId 文档业务 ID
     * @param tenantId   当前租户 ID
     */
    @Transactional
    @Override
    public void deleteDocumentById(String documentId, String tenantId) {
        if (documentId == null || tenantId == null) {
            throw new IllegalArgumentException("Document ID and Tenant ID must be provided");
        }

        // 1. 查询文档确认是否存在，并强校验 tenantId 防止越权
        DocumentDO metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentDO>()
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

        // 3. 异步删除 MinIO 上的文件如果 URL 是合法的 objectName 形式，则可以通过存储服务清理
        if (metadata.getMinioUrl() != null && !metadata.getMinioUrl().isEmpty()) {
            try {
                minioStorageService.deleteFile(metadata.getMinioUrl());
            } catch (Exception e) {
                // 这里只记录日志，不让异常打断事务，保证数据库的先删除
                log.error("[Document Service] Failed to delete document from MinIO. URL: {}", metadata.getMinioUrl(), e);
            }
        }
        
        // 4. 异步向 Kafka 发送文档已被删除的消息，供 ES 等下游组件完成向量删除
        DocumentAsyncTaskDO deleteTask = documentAsyncTaskService.createTask(
                documentId,
                tenantId,
                DocumentAsyncTaskType.DOCUMENT_DELETE,
                DocumentKafkaTopic.DELETE_REQUEST,
                documentId
        );
        String messageId = "msg-" + UUID.randomUUID();
        documentOutboxService.enqueue(
                "DOCUMENT",
                documentId,
                deleteTask.getTaskId(),
                MessageOutboxEventType.DOCUMENT_DELETE_REQUEST,
                DocumentKafkaTopic.DELETE_REQUEST,
                documentId,
                new DocumentDeleteRequestDTO(
                        documentId,
                        tenantId,
                        deleteTask.getTaskId(),
                        messageId,
                        System.currentTimeMillis()
                ));
        log.info("[Document Service] Delete outbox recorded. documentId={}, tenantId={}, taskId={}, messageId={}",
                documentId, tenantId, deleteTask.getTaskId(), messageId);
    }

    /**
     * 从文件名提取扩展名，未识别时回退为 {@code unknown}
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
