package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.common.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
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
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkWriteService;
import com.yoswell.agenticrag.retrieval.document.service.MinioStorageService;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;

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
    private final RedisTemplate<String, Object> redisTemplate;
    private final KnowledgeChunkWriteService knowledgeChunkWriteService;

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
     * 删除指定文档（彻底无残留跨库清除）
     *
     * <p>由于 Agent 场景具有强烈的读写一致性诉求，此处弃用原本的 Kafka 异步彻底清理，转为【同步删除】机制：
     * </p>
     * <ol>
     * <li>MySQL: 移除实体元数据限制用户入口</li>
     * <li>Redis: 清除轮询状态缓存防止 UI 和接口层读取脏状态</li>
     * <li>ElasticSearch: 同步执行 deleteByQuery 以阻止 LLM 召回该知识块（关键防止知识泄露和幻觉）</li>
     * <li>MinIO: 同步销毁原文件防止对象堆积</li>
     * </ol>
     *
     * @param documentId 文档业务 ID
     * @param tenantId   当前租户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteDocumentById(String documentId, String tenantId) {
        if (documentId == null || tenantId == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_ILLEGAL_ARGUMENT);
        }

        // 1. 查询文档确认是否存在，并强校验 tenantId 防止越权
        DocumentDO metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentDO>()
                        .eq("document_id", documentId)
                        .eq("tenant_id", tenantId)
        );

        if (metadata == null) {
            log.warn("[Document Service] Document not found or access denied for deletion. documentId: {}, tenantId: {}", documentId, tenantId);
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        log.info("[Document Service] 收到文档强同步删除请求，准备依次跨库清理. documentId={}", documentId);

        // 2. MySQL: 数据库删除元数据（一旦此事务成功提交，前端将彻底看不见该文档）
        documentMetadataMapper.deleteById(metadata.getId());
        log.info("[Document Service] [1/4] SUCCESS: Document metadata deleted from MySQL. documentId: {}", documentId);

        // 3. Redis: 剔除文档上传轮询机制所依赖的状态缓存，避免缓存读击穿幻象
        String cacheKey = "doc:status:" + documentId + ":" + tenantId;
        try {
            redisTemplate.delete(cacheKey);
            log.info("[Document Service] [2/4] SUCCESS: Redis status cache cleared. key: {}", cacheKey);
        } catch (Exception e) {
            log.error("[Document Service] [2/4] FAILED: Unable to evict Redis cache. key: {}", cacheKey, e);
            // Redis 删除失败不应阻断硬删除主干，降级容忍
        }

        // 4. ElasticSearch (关键!): 强同步移除向量碎片，杜绝 LLM Retrieval Context 被污染或旧知识召回
        // 任何 ES 的调用异常都会冒泡从而引发 @Transactional 事务回滚，确保要么 ES 回归干净要么留着 MySQL
        try {
            knowledgeChunkWriteService.deleteByDocumentId(documentId, tenantId);
            log.info("[Document Service] [3/4] SUCCESS: ElasticSearch chunks hard-deleted synchronously. documentId: {}", documentId);
        } catch (Exception e) {
            log.error("[Document Service] [3/4] FAILED: ElasticSearch deletion failed, transaction will be rolled back. documentId: {}", documentId, e);
            throw new BusinessException(ErrorCode.DOCUMENT_DELETE_FAILED.getCode(), "Failed to delete knowledge chunks securely from Vector Database", e);
        }

        // 5. MinIO: 同步删除对象存储的物理文件（不重要因此容错处理）
        if (metadata.getMinioUrl() != null && !metadata.getMinioUrl().isEmpty()) {
            try {
                minioStorageService.deleteFile(metadata.getMinioUrl());
                log.info("[Document Service] [4/4] SUCCESS: MinIO physical file deleted synchronously. minioUrl: {}", metadata.getMinioUrl());
            } catch (Exception e) {
                // 对象存储垃圾堆积可以忍受，这里 catch 处理但不妨碍主事务，只是发出警告
                log.error("[Document Service] [4/4] FAILED: Failed to delete physical object from MinIO. URL: {}", metadata.getMinioUrl(), e);
            }
        }
        
        log.info("[Document Service] 文档 {} 跨组件闭环同步删除成功全部完成.", documentId);
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
