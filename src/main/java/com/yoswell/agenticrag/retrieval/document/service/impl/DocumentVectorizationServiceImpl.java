package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.retrieval.document.dto.KnowledgeChunkDocumentDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.model.DocumentVectorizationExecutionResult;
import com.yoswell.agenticrag.retrieval.document.parser.DocumentParserFactory;
import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.DocumentParserStrategy;
import com.yoswell.agenticrag.retrieval.document.service.DocumentProcessingStateService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentVectorizationService;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkIndexService;
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
    private final KnowledgeChunkIndexService knowledgeChunkIndexService;
    private final DocumentProcessingStateService documentProcessingStateService;

    public DocumentVectorizationServiceImpl(DocumentMetadataMapper documentMetadataMapper,
                                            MinioStorageService minioStorageService,
                                            DocumentParserFactory documentParserFactory,
                                            EmbeddingModel embeddingModel,
                                            KnowledgeChunkIndexService knowledgeChunkIndexService,
                                            DocumentProcessingStateService documentProcessingStateService) {
        this.documentMetadataMapper = documentMetadataMapper;
        this.minioStorageService = minioStorageService;
        this.documentParserFactory = documentParserFactory;
        this.embeddingModel = embeddingModel;
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
        this.documentProcessingStateService = documentProcessingStateService;
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

        boolean claimed = documentProcessingStateService.transitionStatus(
                metadata.getDocumentId(),
                DocumentProcessingStatus.PARSING,
                List.of(DocumentProcessingStatus.UPLOADED, DocumentProcessingStatus.FAILED)
        );
        if (!claimed) {
            String currentStatus = documentProcessingStateService.getCurrentStatus(metadata.getDocumentId());
            if (DocumentProcessingStatus.PARSING.value().equals(currentStatus)
                    || DocumentProcessingStatus.VECTORIZED.value().equals(currentStatus)) {
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

            List<KnowledgeChunkDocumentDTO> indexedChunks = new ArrayList<>(totalChunks);
            for (int index = 0; index < totalChunks; index++) {
                var chunk = parsedDocument.chunks().get(index);
                int processed = index + 1;
                if (shouldLogEmbeddingProgress(processed, totalChunks)) {
                    log.info("[Offline RAG][EMBED] 进度: documentId={}, {}/{}, chunkId={}",
                            metadata.getDocumentId(), processed, totalChunks, chunk.chunkId());
                }

                Embedding embedding = embeddingModel.embed(chunk.content()).content();
                indexedChunks.add(new KnowledgeChunkDocumentDTO(
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
            knowledgeChunkIndexService.deleteByDocumentId(metadata.getDocumentId(), tenantId);
            knowledgeChunkIndexService.indexChunks(indexedChunks);

            documentProcessingStateService.updateStatus(metadata.getDocumentId(), DocumentProcessingStatus.VECTORIZED);
            log.info("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
                    metadata.getDocumentId(), DocumentProcessingStatus.PARSING.value(), DocumentProcessingStatus.VECTORIZED.value());
            log.info("[Offline RAG][DONE] 文档向量化完成: documentId={}, chunks={}", metadata.getDocumentId(), indexedChunks.size());
            return DocumentVectorizationExecutionResult.success("vectorized chunks=" + indexedChunks.size());
        } catch (Exception exception) {
            log.error("[Offline RAG][FAILED] 文档向量化失败: documentId={}", metadata.getDocumentId(), exception);
            documentProcessingStateService.updateStatus(metadata.getDocumentId(), DocumentProcessingStatus.FAILED);
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
        documentProcessingStateService.updateStatus(documentId, DocumentProcessingStatus.FAILED);
    }

    /**
     * 读取文档元数据，不存在时直接抛错终止链路。
     *
     * @param documentId 文档业务 ID
     * @return 对应元数据
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
