package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoswell.agenticrag.retrieval.document.entity.KnowledgeChunkDocumentDO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.model.DocumentVectorizationExecutionResult;
import com.yoswell.agenticrag.retrieval.document.parser.DocumentParserFactory;
import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.DocumentParserStrategy;
import com.yoswell.agenticrag.retrieval.document.service.DocumentVectorizationService;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkWriteService;
import com.yoswell.agenticrag.retrieval.document.service.MinioStorageService;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 负责把文档从“可读取文本”推进到“可检索知识块”。
 *
 * <p>它串联了元数据校验、文件读取、策略解析、切块 embedding 以及
 * Elasticsearch 写入，是检索预处理链路的核心编排服务。</p>
 */
@Service
public class DocumentVectorizationServiceImpl implements DocumentVectorizationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorizationServiceImpl.class);

    private final DocumentMetadataMapper documentMetadataMapper;
    private final MinioStorageService minioStorageService;
    private final DocumentParserFactory documentParserFactory;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeChunkWriteService knowledgeChunkWriteService;

    public DocumentVectorizationServiceImpl(DocumentMetadataMapper documentMetadataMapper,
                                            MinioStorageService minioStorageService,
                                            DocumentParserFactory documentParserFactory,
                                            EmbeddingModel embeddingModel,
                                            KnowledgeChunkWriteService knowledgeChunkWriteService) {
        this.documentMetadataMapper = documentMetadataMapper;
        this.minioStorageService = minioStorageService;
        this.documentParserFactory = documentParserFactory;
        this.embeddingModel = embeddingModel;
        this.knowledgeChunkWriteService = knowledgeChunkWriteService;
    }

    /**
     * 执行一次完整的文档向量化流程。
     *
     * @param request 向量化阶段的上下文消息
     */
    @Override
    public DocumentVectorizationExecutionResult vectorize(DocumentVectorizeRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.documentId())) {
            throw new IllegalArgumentException("documentId must not be blank");
        }

        DocumentDO metadata = requireMetadata(request.documentId());
        log.info("[Offline RAG][VECTORIZE] 加载文档元数据成功: documentId={}, currentStatus={}, tenantId={}, kbId={}",
            metadata.getDocumentId(), metadata.getStatus(), metadata.getTenantId(), metadata.getKbId());

        if (DocumentProcessingStatus.VECTORIZED.value().equals(metadata.getStatus())) {
            log.info("[Offline RAG][VECTORIZE] 检测到重复消息，文档已是终态，直接跳过: documentId={}",
                    metadata.getDocumentId());
            return DocumentVectorizationExecutionResult.skipped("document already vectorized");
        }

        boolean claimed = transitionStatus(
                metadata.getDocumentId(),
                DocumentProcessingStatus.PARSING,
                List.of(DocumentProcessingStatus.UPLOADED, DocumentProcessingStatus.FAILED)
        );
        if (!claimed) {
            DocumentProcessingStatus currentStatus = getCurrentStatus(metadata.getDocumentId());
            if (DocumentProcessingStatus.PARSING.value().equals(currentStatus != null ? currentStatus.value() : null)
                    || DocumentProcessingStatus.VECTORIZED.value().equals(currentStatus != null ? currentStatus.value() : null)) {
                log.info("[Offline RAG][VECTORIZE] 检测到重复或并发中的向量化任务，直接跳过: documentId={}, currentStatus={}",
                        metadata.getDocumentId(), currentStatus);
                return DocumentVectorizationExecutionResult.skipped("document is already processing or vectorized");
            }
            log.warn("[Offline RAG][VECTORIZE] 文档状态不符合向量化前置条件，仍尝试继续处理: documentId={}, currentStatus={}",
                    metadata.getDocumentId(), currentStatus);
        }

        try {
            String sourceFileUrl = firstNonBlank(request.fileUrl(), metadata.getMinioUrl());
            String sourceFileName = firstNonBlank(request.fileName(), metadata.getFileName());
            String sourceExtension = firstNonBlank(request.fileExtension(), metadata.getFileExtension());
            String tenantId = metadata.getTenantId();
            String kbId = metadata.getKbId();
            List<String> allowedRoles = request.allowedRoles() == null || request.allowedRoles().isEmpty()
                    ? splitRoles(metadata.getAllowedRoles())
                    : request.allowedRoles();

            log.info("[Offline RAG][SOURCE] 已解析向量化输入源: documentId={}, tenantId={}, kbId={}, fileName={}, extension={}",
                metadata.getDocumentId(), tenantId, kbId, sourceFileName, sourceExtension);

            DocumentParserStrategy parserStrategy = documentParserFactory.getStrategy(sourceExtension);
            log.info("[Offline RAG][PARSE] 选择解析策略: documentId={}, strategy={}",
                metadata.getDocumentId(), parserStrategy.getClass().getSimpleName());

            log.info("[Offline RAG][PARSE] 开始读取并解析文本: documentId={}", metadata.getDocumentId());
            String content = minioStorageService.readUtf8String(sourceFileUrl);
            ParsedDocument parsedDocument = parserStrategy
                    .parse(new DocumentParseSource(sourceFileUrl, sourceFileName, sourceExtension, content));

            int totalChunks = parsedDocument.chunks().size();
            log.info("[Offline RAG][PARSE] 文档解析完成: documentId={}, chunkCount={}", metadata.getDocumentId(), totalChunks);

            List<KnowledgeChunkDocumentDO> indexedChunks = new ArrayList<>(totalChunks);
            for (int index = 0; index < totalChunks; index++) {
                var chunk = parsedDocument.chunks().get(index);
                int processed = index + 1;
                if (shouldLogEmbeddingProgress(processed, totalChunks)) {
                    log.info("[Offline RAG][EMBED] 进度: documentId={}, {}/{}, chunkId={}",
                            metadata.getDocumentId(), processed, totalChunks, chunk.chunkId());
                }

                Embedding embedding = embeddingModel.embed(chunk.content()).content();
                indexedChunks.add(new KnowledgeChunkDocumentDO(
                        chunk.chunkId(),
                        metadata.getDocumentId(),
                        sourceFileName,
                        tenantId,
                        kbId,
                        allowedRoles,
                        chunk.chunkIndex(),
                        chunk.content(),
                        embedding.vectorAsList()
                ));
            }

            log.info("[Offline RAG][EMBED] 向量化完成，准备写入检索索引: documentId={}, chunkCount={}",
                    metadata.getDocumentId(), indexedChunks.size());

            log.info("[Offline RAG][INDEX] 开始写入 ES 检索索引: documentId={}, chunkCount={}",
                    metadata.getDocumentId(), indexedChunks.size());
            knowledgeChunkWriteService.deleteByDocumentId(metadata.getDocumentId(), tenantId);
            knowledgeChunkWriteService.indexChunks(indexedChunks);

            updateStatus(metadata.getDocumentId(), DocumentProcessingStatus.VECTORIZED);
            log.info("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
                    metadata.getDocumentId(), DocumentProcessingStatus.PARSING.value(), DocumentProcessingStatus.VECTORIZED.value());
            log.info("[Offline RAG][DONE] 文档向量化完成: documentId={}, chunks={}", metadata.getDocumentId(), indexedChunks.size());
            return DocumentVectorizationExecutionResult.success("vectorized chunks=" + indexedChunks.size());
        } catch (Exception exception) {
            log.error("[Offline RAG][FAILED] 文档向量化失败: documentId={}", metadata.getDocumentId(), exception);
            updateStatus(metadata.getDocumentId(), DocumentProcessingStatus.FAILED);
            log.warn("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
                    metadata.getDocumentId(), DocumentProcessingStatus.PARSING.value(), DocumentProcessingStatus.FAILED.value());
            throw exception;
        }
    }

    /**
     * 在补偿或死信场景下把文档直接标记为失败。
     *
     * @param documentId 文档业务 ID
     */
    @Override
    public void markFailed(String documentId) {
        updateStatus(documentId, DocumentProcessingStatus.FAILED);
    }

    /**
     * 状态 CAS 迁移：仅当文档处于允许的前置状态之一时，才将其推进到目标状态。
     *
     * @param documentId              文档业务 ID
     * @param targetStatus            目标状态
     * @param expectedCurrentStatuses 前置状态白名单
     * @return 迁移成功返回 {@code true}，状态不匹配返回 {@code false}
     */
    @Transactional
    private boolean transitionStatus(String documentId,
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

    /**
     * 无条件强制更新文档状态（用于写入终态 VECTORIZED / FAILED）。
     *
     * @param documentId   文档业务 ID
     * @param targetStatus 目标状态
     */
    @Transactional
    private void updateStatus(String documentId, DocumentProcessingStatus targetStatus) {
        if (!StringUtils.hasText(documentId) || targetStatus == null) {
            return;
        }
        LambdaUpdateWrapper<DocumentDO> updateWrapper = new LambdaUpdateWrapper<DocumentDO>()
                .eq(DocumentDO::getDocumentId, documentId)
                .set(DocumentDO::getStatus, targetStatus.value());
        documentMetadataMapper.update(null, updateWrapper);
    }

    /**
     * 读取文档当前状态。
     *
     * @param documentId 文档业务 ID
     * @return 当前状态，文档不存在时返回 {@code null}
     */
    private DocumentProcessingStatus getCurrentStatus(String documentId) {
        DocumentDO metadata = documentMetadataMapper.selectOne(
                new LambdaQueryWrapper<DocumentDO>()
                        .eq(DocumentDO::getDocumentId, documentId)
                        .select(DocumentDO::getStatus)
        );
        return metadata == null ? null : metadata.getStatus();
    }

    /**
     * 读取文档元数据，不存在时直接抛错终止链路。
     */
    private DocumentDO requireMetadata(String documentId) {
        DocumentDO metadata = documentMetadataMapper.selectOne(
                new LambdaQueryWrapper<DocumentDO>().eq(DocumentDO::getDocumentId, documentId)
        );
        if (metadata == null) {
            throw new RuntimeException("Document metadata not found for documentId=" + documentId);
        }
        return metadata;
    }

    /**
     * 把数据库里逗号分隔的角色串还原成列表。
     *
     * @param allowedRoles 持久化后的角色字段
     * @return 角色列表
     */
    private List<String> splitRoles(String allowedRoles) {
        if (!StringUtils.hasText(allowedRoles)) {
            return List.of("ROLE_USER");
        }
        return Arrays.stream(allowedRoles.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 返回第一个非空白字符串，用于在消息体和数据库字段之间做兜底合并。
     *
     * @param preferred 优先值
     * @param fallback 回退值
     * @return 最终使用的值
     */
    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private boolean shouldLogEmbeddingProgress(int processed, int total) {
        if (total <= 5) {
            return true;
        }
        return processed == 1 || processed == total || processed % 20 == 0;
    }
}
