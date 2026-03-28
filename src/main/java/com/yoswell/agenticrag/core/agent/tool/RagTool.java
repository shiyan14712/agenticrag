package com.yoswell.agenticrag.core.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDto;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResult;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunk;
import com.yoswell.agenticrag.core.agent.rag.RerankerClient;
import com.yoswell.agenticrag.retrieval.document.index.KnowledgeChunkIndexService;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

@Component
public class RagTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);
    private static final int RRF_K = 60;

    private final KnowledgeChunkIndexService knowledgeChunkIndexService;
    private final EmbeddingModel embeddingModel;
    private final RerankerClient rerankerClient;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    @Value("${rag.retrieval.knn-top-k:20}")
    private int knnTopK;

    @Value("${rag.retrieval.bm25-top-k:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.rerank-top-n:5}")
    private int rerankTopN;

    public RagTool(KnowledgeChunkIndexService knowledgeChunkIndexService,
                   EmbeddingModel embeddingModel,
                   RerankerClient rerankerClient,
                   RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
        this.embeddingModel = embeddingModel;
        this.rerankerClient = rerankerClient;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    @Tool("search_enterprise_knowledge")
    public String searchEnterpriseKnowledge(String query) {
        log.info("Executing RagTool with query: {}", query);
        try {
            String tenantId = "default";
            String role = "ROLE_USER";
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
                tenantId = user.getTenantId();
                role = user.getRole();
            }

            Embedding queryVector = embeddingModel.embed(query).content();
            List<String> allowedRoles = List.of(role);
            log.debug("Building hybrid retrieval request for tenant={}, roles={}", tenantId, allowedRoles);

            List<RetrievedChunk> bm25Hits = knowledgeChunkIndexService.searchByKeyword(query, tenantId, allowedRoles, bm25TopK);
            List<RetrievedChunk> knnHits = knowledgeChunkIndexService.searchByVector(queryVector.vectorAsList(), tenantId, allowedRoles, knnTopK);
            List<RetrievedChunk> fusedChunks = calculateRrfFusion(bm25Hits, knnHits);
            List<RetrievedChunk> rerankedChunks = crossAttentionRerank(fusedChunks, query);
            List<RetrievedChunk> topChunks = rerankedChunks.stream().limit(rerankTopN).toList();

            RagSearchResult result = new RagSearchResult(
                    buildObservation(topChunks),
                    topChunks,
                    buildCitations(topChunks)
            );
            ragRetrievalContextHolder.publish(result);
            log.info("Rag search completed, returning top {} chunks.", topChunks.size());
            return result.observation();
        } catch (Exception e) {
            log.error("Error during enterprise knowledge search", e);
            throw new RuntimeException("Search failed", e);
        }
    }

    List<RetrievedChunk> calculateRrfFusion(List<RetrievedChunk> bm25Hits, List<RetrievedChunk> knnHits) {
        Map<String, RetrievedChunk> chunkRegistry = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        mergeRrfScores(bm25Hits, chunkRegistry, rrfScores);
        mergeRrfScores(knnHits, chunkRegistry, rrfScores);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> withScore(chunkRegistry.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    List<RetrievedChunk> crossAttentionRerank(List<RetrievedChunk> fusedChunks, String query) {
        log.debug("Reranking {} documents against query...", fusedChunks.size());
        return rerankerClient.rerank(query, fusedChunks);
    }

    private void mergeRrfScores(List<RetrievedChunk> hits,
                                Map<String, RetrievedChunk> chunkRegistry,
                                Map<String, Double> rrfScores) {
        for (int index = 0; index < hits.size(); index++) {
            RetrievedChunk hit = hits.get(index);
            chunkRegistry.putIfAbsent(hit.chunkId(), hit);
            rrfScores.merge(hit.chunkId(), 1.0d / (RRF_K + index + 1), Double::sum);
        }
    }

    private RetrievedChunk withScore(RetrievedChunk chunk, double score) {
        return new RetrievedChunk(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.documentName(),
                chunk.tenantId(),
                chunk.kbId(),
                chunk.allowedRoles(),
                chunk.chunkIndex(),
                chunk.content(),
                score
        );
    }

    private String buildObservation(List<RetrievedChunk> topChunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk topChunk : topChunks) {
            builder.append("[Doc ID: ")
                    .append(topChunk.documentId())
                    .append("][Chunk ID: ")
                    .append(topChunk.chunkId())
                    .append("] ")
                    .append(topChunk.content())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private List<CitationDto> buildCitations(List<RetrievedChunk> topChunks) {
        ArrayList<CitationDto> citations = new ArrayList<>(topChunks.size());
        for (RetrievedChunk topChunk : topChunks) {
            citations.add(new CitationDto(
                    topChunk.documentId(),
                    topChunk.documentName(),
                    topChunk.chunkId(),
                    topChunk.score()
            ));
        }
        return List.copyOf(citations);
    }
}
