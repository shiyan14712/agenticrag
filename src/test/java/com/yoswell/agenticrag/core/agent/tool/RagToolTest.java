package com.yoswell.agenticrag.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResult;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunk;
import com.yoswell.agenticrag.core.agent.rag.RerankerClient;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkIndexService;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

@ExtendWith(MockitoExtension.class)
class RagToolTest {

    @Mock
    private KnowledgeChunkIndexService knowledgeChunkIndexService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private RerankerClient rerankerClient;

    @Mock
    private RagRetrievalContextHolder ragRetrievalContextHolder;

    private RagTool ragTool;

    @BeforeEach
    void setUp() {
        ragTool = new RagTool(knowledgeChunkIndexService, embeddingModel, rerankerClient, ragRetrievalContextHolder);
        ReflectionTestUtils.setField(ragTool, "bm25TopK", 20);
        ReflectionTestUtils.setField(ragTool, "knnTopK", 20);
        ReflectionTestUtils.setField(ragTool, "rerankTopN", 2);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchEnterpriseKnowledgeUsesTenantContextAndPublishesCitations() {
        TenantUser user = new TenantUser("user-1", "tenant-a", "ROLE_ANALYST");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST")))
        );

        when(embeddingModel.embed("budget")).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(knowledgeChunkIndexService.searchByKeyword("budget", "tenant-a", List.of("ROLE_ANALYST"), 20))
                .thenReturn(List.of(chunk("chk-1", "doc-1", 0.9), chunk("chk-2", "doc-2", 0.4)));
        when(knowledgeChunkIndexService.searchByVector(List.of(0.1f, 0.2f), "tenant-a", List.of("ROLE_ANALYST"), 20))
                .thenReturn(List.of(chunk("chk-2", "doc-2", 0.8), chunk("chk-1", "doc-1", 0.5)));
        when(rerankerClient.rerank(eq("budget"), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        String observation = ragTool.searchEnterpriseKnowledge("budget");

        assertThat(observation).contains("[Doc ID: doc-1][Chunk ID: chk-1]");
        verify(ragRetrievalContextHolder).publish(org.mockito.ArgumentMatchers.argThat((RagSearchResult result) ->
                result.citations().size() == 2
                        && result.citations().get(0).docId().equals("doc-1")
                        && result.citations().get(0).chunkId().equals("chk-1")));
    }

    @Test
    void calculateRrfFusionPrefersChunksReturnedByBothChannels() {
        List<RetrievedChunk> fused = ragTool.calculateRrfFusion(
                List.of(chunk("shared", "doc-1", 0.9), chunk("only-bm25", "doc-2", 0.8)),
                List.of(chunk("shared", "doc-1", 0.7), chunk("only-knn", "doc-3", 0.6))
        );

        assertThat(fused).extracting(RetrievedChunk::chunkId)
                .containsExactly("shared", "only-bm25", "only-knn");
    }

    private RetrievedChunk chunk(String chunkId, String documentId, double score) {
        return new RetrievedChunk(
                chunkId,
                documentId,
                documentId + ".txt",
                "tenant-a",
                "kb-1",
                List.of("ROLE_ANALYST"),
                0,
                "content for " + chunkId,
                score
        );
    }
}
