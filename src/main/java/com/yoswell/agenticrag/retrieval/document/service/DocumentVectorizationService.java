package com.yoswell.agenticrag.retrieval.document.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.dto.KnowledgeChunkDocumentDTO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.parser.DocumentParserFactory;
import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.DocumentParserStrategy;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 负责把文档从“可读取文本”推进到“可检索知识块”。
 *
 * <p>它串联了元数据校验、文件读取、策略解析、切块 embedding 以及
 * Elasticsearch 写入，是检索预处理链路的核心编排服务。</p>
 */
@Service
public class DocumentVectorizationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorizationService.class);

    private final DocumentMetadataMapper documentMetadataMapper;
    private final MinioStorageService minioStorageService;
    private final DocumentParserFactory documentParserFactory;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeChunkIndexService knowledgeChunkIndexService;

    public DocumentVectorizationService(DocumentMetadataMapper documentMetadataMapper,
                                        MinioStorageService minioStorageService,
                                        DocumentParserFactory documentParserFactory,
                                        EmbeddingModel embeddingModel,
                                        KnowledgeChunkIndexService knowledgeChunkIndexService) {
        this.documentMetadataMapper = documentMetadataMapper;
        this.minioStorageService = minioStorageService;
        this.documentParserFactory = documentParserFactory;
        this.embeddingModel = embeddingModel;
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
    }

    /**
     * 执行一次完整的文档向量化流程。
     *
     * @param request 向量化阶段的上下文消息
     */
    @Transactional
    public void vectorize(DocumentVectorizeRequestDTO request) {
        if (request == null || !StringUtils.hasText(request.documentId())) {
            throw new IllegalArgumentException("documentId must not be blank");
        }

        DocumentDO metadata = requireMetadata(request.documentId());
        log.info("[Offline RAG][VECTORIZE] 加载文档元数据成功: documentId={}, currentStatus={}, tenantId={}, kbId={}",
            metadata.getDocumentId(), metadata.getStatus(), metadata.getTenantId(), metadata.getKbId());

        String statusBeforeParsing = metadata.getStatus();
        updateStatus(metadata, DocumentProcessingStatus.PARSING);
        log.info("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
            metadata.getDocumentId(), statusBeforeParsing, metadata.getStatus());

        try {
            String sourceFileUrl = firstNonBlank(request.fileUrl(), metadata.getMinioUrl());
            String sourceFileName = firstNonBlank(request.fileName(), metadata.getFileName());
            String sourceExtension = firstNonBlank(request.fileExtension(), metadata.getFileExtension());
            String tenantId = firstNonBlank(request.tenantId(), metadata.getTenantId());
            String kbId = firstNonBlank(request.kbId(), metadata.getKbId());
            List<String> allowedRoles = request.allowedRoles() == null || request.allowedRoles().isEmpty()
                    ? splitRoles(metadata.getAllowedRoles())
                    : List.copyOf(request.allowedRoles());

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
            knowledgeChunkIndexService.indexChunks(indexedChunks);

                    String statusBeforeVectorized = metadata.getStatus();
            updateStatus(metadata, DocumentProcessingStatus.VECTORIZED);
                    log.info("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
                        metadata.getDocumentId(), statusBeforeVectorized, metadata.getStatus());
                    log.info("[Offline RAG][DONE] 文档向量化完成: documentId={}, chunks={}", metadata.getDocumentId(), indexedChunks.size());
        } catch (Exception exception) {
                    log.error("[Offline RAG][FAILED] 文档向量化失败: documentId={}", metadata.getDocumentId(), exception);
                    String statusBeforeFailed = metadata.getStatus();
            updateStatus(metadata, DocumentProcessingStatus.FAILED);
                    log.warn("[Offline RAG][VECTORIZE] 状态迁移: documentId={}, {} -> {}",
                        metadata.getDocumentId(), statusBeforeFailed, metadata.getStatus());
            throw exception;
        }
    }

    /**
     * 在补偿或死信场景下把文档直接标记为失败。
     *
     * @param documentId 文档业务 ID
     */
    @Transactional
    public void markFailed(String documentId) {
        updateStatus(requireMetadata(documentId), DocumentProcessingStatus.FAILED);
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
     * 更新文档处理状态并回写数据库。
     *
     * @param metadata 文档元数据
     * @param status 目标状态
     */
    private void updateStatus(DocumentDO metadata, DocumentProcessingStatus status) {
        metadata.setStatus(status.value());
        documentMetadataMapper.updateById(metadata);
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
