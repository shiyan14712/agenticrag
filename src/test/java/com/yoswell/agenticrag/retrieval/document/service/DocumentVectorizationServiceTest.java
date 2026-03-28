package com.yoswell.agenticrag.retrieval.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentMetadata;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkDocument;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkIndexService;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.parser.DocumentParserFactory;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.MinerUMarkdownStrategy;
import com.yoswell.agenticrag.retrieval.document.parser.strategy.StandardTxtStrategy;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

@ExtendWith(MockitoExtension.class)
class DocumentVectorizationServiceTest {

    @Mock
    private DocumentMetadataMapper documentMetadataMapper;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private KnowledgeChunkIndexService knowledgeChunkIndexService;

    private DocumentVectorizationService documentVectorizationService;

    @BeforeEach
    void setUp() {
        DocumentParserFactory parserFactory = new DocumentParserFactory(
                new MinerUMarkdownStrategy(),
                new StandardTxtStrategy()
        );
        documentVectorizationService = new DocumentVectorizationService(
                documentMetadataMapper,
                minioStorageService,
                parserFactory,
                embeddingModel,
                knowledgeChunkIndexService
        );
    }

    @Test
    void vectorizeReadsContentParsesChunksIndexesThemAndUpdatesStatus() {
        List<String> statusHistory = new ArrayList<>();
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId(1L);
        metadata.setDocumentId("doc-1");
        metadata.setTenantId("tenant-a");
        metadata.setKbId("kb-1");
        metadata.setFileName("notes.txt");
        metadata.setFileExtension("txt");
        metadata.setMinioUrl("http://minio/agenticrag/doc-1/notes.txt");
        metadata.setAllowedRoles("ROLE_USER");

        when(documentMetadataMapper.selectOne(any())).thenReturn(metadata);
        when(minioStorageService.readUtf8String("http://minio/agenticrag/doc-1/notes.txt"))
                .thenReturn("第一段内容。\n\n第二段内容。\n\n第三段内容。");
        when(embeddingModel.embed(any(String.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f, 0.3f})));
        doAnswer(invocation -> {
            DocumentMetadata updated = invocation.getArgument(0);
            statusHistory.add(updated.getStatus());
            return 1;
        }).when(documentMetadataMapper).updateById(any(DocumentMetadata.class));

        documentVectorizationService.vectorize(new DocumentVectorizeRequestDTO(
                "doc-1",
                "tenant-a",
                "kb-1",
                "notes.txt",
                "http://minio/agenticrag/doc-1/notes.txt",
                "txt",
                List.of("ROLE_USER"),
                System.currentTimeMillis()
        ));

        verify(knowledgeChunkIndexService).indexChunks(org.mockito.ArgumentMatchers.argThat((List<KnowledgeChunkDocument> chunks) ->
                !chunks.isEmpty()
                        && chunks.stream().allMatch(chunk -> chunk.documentId().equals("doc-1"))
                        && chunks.stream().allMatch(chunk -> chunk.tenantId().equals("tenant-a"))));
        assertThat(statusHistory).containsExactly("PARSING", "VECTORIZED");
    }
}
