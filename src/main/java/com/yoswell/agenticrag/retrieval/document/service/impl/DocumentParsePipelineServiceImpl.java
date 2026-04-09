package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.DocumentAsyncTaskDO;
import com.yoswell.agenticrag.retrieval.document.reliability.model.DocumentAsyncTaskType;
import com.yoswell.agenticrag.retrieval.document.reliability.service.DocumentAsyncTaskService;
import com.yoswell.agenticrag.retrieval.document.reliability.service.DocumentOutboxService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentParsePipelineService;
import com.yoswell.agenticrag.retrieval.document.service.MineruDocumentParseService;
import com.yoswell.agenticrag.retrieval.document.service.MinioStorageService;

/**
 * 文档解析编排服务：把 parse 请求转换为可向量化的 Markdown 输入。
 */
@Service
public class DocumentParsePipelineServiceImpl implements DocumentParsePipelineService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsePipelineServiceImpl.class);

    private final MinioStorageService minioStorageService;
    private final MineruDocumentParseService mineruDocumentParseService;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final DocumentOutboxService documentOutboxService;
    private final DocumentAsyncTaskService documentAsyncTaskService;
    private final DocumentKafkaProperties kafkaProperties;

    public DocumentParsePipelineServiceImpl(MinioStorageService minioStorageService,
                                            MineruDocumentParseService mineruDocumentParseService,
                                            DocumentMetadataMapper documentMetadataMapper,
                                            DocumentOutboxService documentOutboxService,
                                            DocumentAsyncTaskService documentAsyncTaskService,
                                            DocumentKafkaProperties kafkaProperties) {
        this.minioStorageService = minioStorageService;
        this.mineruDocumentParseService = mineruDocumentParseService;
        this.documentMetadataMapper = documentMetadataMapper;
        this.documentOutboxService = documentOutboxService;
        this.documentAsyncTaskService = documentAsyncTaskService;
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * 解析文档并可靠投递向量化请求。
     *
     * @param request parse 阶段消息
     * @return 执行结果
     */
    @Override
    public DocumentParsePipelineService.ParseDispatchResult parseAndDispatch(DocumentParseRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.documentId())) {
            throw new IllegalArgumentException("documentId must not be blank");
        }

        DocumentDO metadata = requireMetadata(request.documentId());
        String tenantId = firstNonBlank(request.tenantId(), metadata.getTenantId());
        String kbId = firstNonBlank(request.kbId(), metadata.getKbId());
        String sourceFileName = firstNonBlank(request.fileName(), metadata.getFileName());
        String sourceFileExtension = firstNonBlank(request.fileExtension(), metadata.getFileExtension());
        String sourceFileUrl = firstNonBlank(request.fileUrl(), metadata.getMinioUrl());
        if (!StringUtils.hasText(sourceFileUrl)) {
            throw new IllegalStateException("Missing source file URL for documentId=" + request.documentId());
        }

        byte[] sourceBytes = minioStorageService.readFile(sourceFileUrl);
        String sourceContentType = resolveContentType(sourceFileName, sourceFileExtension);
        log.info("[Offline RAG][PARSE_PIPELINE] Parsing source via MinerU. documentId={}, fileName={}, fileUrl={}",
                request.documentId(), sourceFileName, sourceFileUrl);

        MineruDocumentParseService.ParseResult parseResult = mineruDocumentParseService.parseToMarkdown(
                sourceFileName,
                sourceBytes,
                sourceContentType);

        String markdownFileUrl = uploadParsedMarkdown(request.documentId(), sourceFileName, parseResult.markdown());
        List<String> allowedRoles = normalizeAllowedRoles(request.allowedRoles(), metadata.getAllowedRoles());

        DocumentAsyncTaskDO vectorizeTask = documentAsyncTaskService.getOrCreateTask(
                request.documentId(),
                tenantId,
                DocumentAsyncTaskType.DOCUMENT_VECTORIZATION,
                kafkaProperties.getTopics().getVectorizeRequest(),
                request.documentId());

        String vectorizeMessageId = buildVectorizeMessageId(request.documentId(), vectorizeTask.getTaskId());
        documentOutboxService.enqueue(
                "DOCUMENT",
                request.documentId(),
                vectorizeTask.getTaskId(),
                "DOCUMENT_VECTORIZATION_REQUEST",
                kafkaProperties.getTopics().getVectorizeRequest(),
                request.documentId(),
                new DocumentVectorizeRequestDTO(
                        request.documentId(),
                        tenantId,
                        kbId,
                        sourceFileName,
                        markdownFileUrl,
                        "md",
                        allowedRoles,
                        vectorizeMessageId,
                        System.currentTimeMillis()
                ));

        log.info("[Offline RAG][PARSE_PIPELINE] Parsed markdown ready and vectorize outbox created. documentId={}, vectorizeTaskId={}, messageId={}, mineruTaskId={}",
                request.documentId(), vectorizeTask.getTaskId(), vectorizeMessageId, parseResult.mineruTaskId());

        return new DocumentParsePipelineService.ParseDispatchResult(
            markdownFileUrl,
            parseResult.mineruTaskId(),
            vectorizeTask.getTaskId(),
            vectorizeMessageId);
    }

    private DocumentDO requireMetadata(String documentId) {
        DocumentDO metadata = documentMetadataMapper.selectOne(new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getDocumentId, documentId));
        if (metadata == null) {
            throw new IllegalStateException("Document metadata not found for documentId=" + documentId);
        }
        return metadata;
    }

    private String uploadParsedMarkdown(String documentId, String sourceFileName, String markdown) {
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalStateException("Parsed markdown is empty for documentId=" + documentId);
        }

        byte[] markdownBytes = markdown.getBytes(StandardCharsets.UTF_8);
        String markdownFileName = toMarkdownFileName(sourceFileName);
        String objectName = documentId + "/parsed/" + markdownFileName;
        return minioStorageService.uploadFile(
                objectName,
                new ByteArrayInputStream(markdownBytes),
                markdownBytes.length,
                "text/markdown; charset=UTF-8");
    }

    private List<String> normalizeAllowedRoles(List<String> requestRoles, String persistedRoles) {
        if (requestRoles != null && !requestRoles.isEmpty()) {
            return requestRoles.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
        }

        if (!StringUtils.hasText(persistedRoles)) {
            return List.of("ROLE_USER");
        }

        List<String> roles = Arrays.stream(persistedRoles.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        return roles.isEmpty() ? List.of("ROLE_USER") : roles;
    }

    private String toMarkdownFileName(String sourceFileName) {
        String normalized = StringUtils.hasText(sourceFileName) ? sourceFileName : "document";
        int dotIndex = normalized.lastIndexOf('.');
        String baseName = dotIndex > 0 ? normalized.substring(0, dotIndex) : normalized;
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!StringUtils.hasText(sanitized)) {
            sanitized = "document";
        }
        return sanitized + ".md";
    }

    private String resolveContentType(String fileName, String fileExtension) {
        String extension = fileExtension;
        if (!StringUtils.hasText(extension) && StringUtils.hasText(fileName) && fileName.contains(".")) {
            extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        }
        if (!StringUtils.hasText(extension)) {
            return "application/octet-stream";
        }

        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String buildVectorizeMessageId(String documentId, String vectorizeTaskId) {
        if (StringUtils.hasText(vectorizeTaskId)) {
            return "msg-vectorize-" + vectorizeTaskId;
        }
        return "msg-vectorize-" + (StringUtils.hasText(documentId) ? documentId : UUID.randomUUID());
    }

}