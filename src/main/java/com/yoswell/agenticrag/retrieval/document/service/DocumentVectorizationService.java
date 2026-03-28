package com.yoswell.agenticrag.retrieval.document.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yoswell.agenticrag.retrieval.document.dto.DocumentVectorizeRequest;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkDocument;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkIndexService;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.model.DocumentProcessingStatus;
import com.yoswell.agenticrag.retrieval.document.parser.DocumentParserFactory;
import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

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

    @Transactional
    public void vectorize(DocumentVectorizeRequest request) {
        DocumentMetadata metadata = requireMetadata(request.documentId());
        updateStatus(metadata, DocumentProcessingStatus.PARSING);

        try {
            String sourceFileUrl = firstNonBlank(request.fileUrl(), metadata.getMinioUrl());
            String sourceFileName = firstNonBlank(request.fileName(), metadata.getFileName());
            String sourceExtension = firstNonBlank(request.fileExtension(), metadata.getFileExtension());
            String tenantId = firstNonBlank(request.tenantId(), metadata.getTenantId());
            String kbId = firstNonBlank(request.kbId(), metadata.getKbId());
            List<String> allowedRoles = request.allowedRoles() == null || request.allowedRoles().isEmpty()
                    ? splitRoles(metadata.getAllowedRoles())
                    : List.copyOf(request.allowedRoles());

            String content = minioStorageService.readUtf8String(sourceFileUrl);
            ParsedDocument parsedDocument = documentParserFactory.getStrategy(sourceExtension)
                    .parse(new DocumentParseSource(sourceFileUrl, sourceFileName, sourceExtension, content));

            List<KnowledgeChunkDocument> indexedChunks = new ArrayList<>(parsedDocument.chunks().size());
            parsedDocument.chunks().forEach(chunk -> {
                Embedding embedding = embeddingModel.embed(chunk.content()).content();
                indexedChunks.add(new KnowledgeChunkDocument(
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
            });

            knowledgeChunkIndexService.indexChunks(indexedChunks);
            updateStatus(metadata, DocumentProcessingStatus.VECTORIZED);
            log.info("Document vectorization completed: documentId={}, chunks={}", metadata.getDocumentId(), indexedChunks.size());
        } catch (Exception exception) {
            updateStatus(metadata, DocumentProcessingStatus.FAILED);
            throw exception;
        }
    }

    @Transactional
    public void markFailed(String documentId) {
        updateStatus(requireMetadata(documentId), DocumentProcessingStatus.FAILED);
    }

    private DocumentMetadata requireMetadata(String documentId) {
        DocumentMetadata metadata = documentMetadataMapper.selectOne(
                new QueryWrapper<DocumentMetadata>().eq("document_id", documentId)
        );
        if (metadata == null) {
            throw new RuntimeException("Document metadata not found for documentId=" + documentId);
        }
        return metadata;
    }

    private void updateStatus(DocumentMetadata metadata, DocumentProcessingStatus status) {
        metadata.setStatus(status.value());
        documentMetadataMapper.updateById(metadata);
    }

    private List<String> splitRoles(String allowedRoles) {
        if (!StringUtils.hasText(allowedRoles)) {
            return List.of("ROLE_USER");
        }
        return Arrays.stream(allowedRoles.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }
}
