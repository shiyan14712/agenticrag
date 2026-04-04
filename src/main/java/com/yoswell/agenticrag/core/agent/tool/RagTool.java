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
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.core.agent.rag.RerankerClient;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkIndexService;
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
        log.info("[RAG TOOL] LLM 已决策调用工具 search_enterprise_knowledge，开始企业知识检索。queryPreview={}", summarizeQuery(query));
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof TenantUser user)) {
                throw new IllegalStateException("Authentication missing or invalid. Strict tenant isolation requires a valid user context.");
            }
            
            // 按照设计要求：本系统中用户ID即为租户级别的隔离单元
            String tenantId = user.getUserId();
            String role = user.getRole();

            Embedding queryVector = embeddingModel.embed(query).content();
            List<String> allowedRoles = List.of(role);
            log.debug("[RAG TOOL] Building hybrid retrieval request for tenant={}, roles={}", tenantId, allowedRoles);

            List<RetrievedChunkDTO> bm25Hits = knowledgeChunkIndexService.searchByKeyword(query, tenantId, allowedRoles, bm25TopK);
            List<RetrievedChunkDTO> knnHits = knowledgeChunkIndexService.searchByVector(queryVector.vectorAsList(), tenantId, allowedRoles, knnTopK);
            List<RetrievedChunkDTO> fusedChunks = calculateRrfFusion(bm25Hits, knnHits);
            log.info("[RAG TOOL] 混合检索完成: bm25Hits={}, knnHits={}, fusedHits={}", bm25Hits.size(), knnHits.size(), fusedChunks.size());
            List<RetrievedChunkDTO> rerankedChunks = crossAttentionRerank(fusedChunks, query);
            List<RetrievedChunkDTO> topChunks = rerankedChunks.stream().limit(rerankTopN).toList();

            RagSearchResultDTO result = new RagSearchResultDTO(
                    buildObservation(topChunks),
                    topChunks,
                    buildCitations(topChunks)
            );
            ragRetrievalContextHolder.publish(result);
            log.info("[RAG TOOL] 工具执行完成，已向 LLM 返回 top {} 条知识片段。", topChunks.size());
            return result.observation();
        } catch (RuntimeException exception) {
            log.error("[RAG TOOL] Error during enterprise knowledge search", exception);
            throw new RuntimeException("Search failed", exception);
        }
    }

    List<RetrievedChunkDTO> calculateRrfFusion(List<RetrievedChunkDTO> bm25Hits, List<RetrievedChunkDTO> knnHits) {
        Map<String, RetrievedChunkDTO> chunkRegistry = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        mergeRrfScores(bm25Hits, chunkRegistry, rrfScores);
        mergeRrfScores(knnHits, chunkRegistry, rrfScores);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> withScore(chunkRegistry.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    List<RetrievedChunkDTO> crossAttentionRerank(List<RetrievedChunkDTO> fusedChunks, String query) {
        log.info("[RAG TOOL] 进入重排阶段，准备调用 reranker，候选片段数={}", fusedChunks.size());
        return rerankerClient.rerank(query, fusedChunks);
    }

    private String summarizeQuery(String query) {
        if (query == null) {
            return "<null>";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 50) + "...";
    }

    /**
     * Calculates RRF scores for each chunk and adds them to the rrfScores map.
     *
     * RRF(d) = \sum_{i=1}^{m} \frac{1}{RRF_K + rank(d)}
     * d for Document-Block, m for Num of Search Channels
     * rank(d) for rank of d in the search channel, RRF_K for smoothing constant.
     * @param hits List of retrieved chunks from a single search channel
     * @param chunkRegistry Registry to track unique chunks across channels
     * @param rrfScores Accumulated RRF scores map
     */
    private void mergeRrfScores(List<RetrievedChunkDTO> hits,
                                Map<String, RetrievedChunkDTO> chunkRegistry,
                                Map<String, Double> rrfScores) {
        for (int index = 0; index < hits.size(); index++) {
            RetrievedChunkDTO hit = hits.get(index);
            chunkRegistry.putIfAbsent(hit.chunkId(), hit);
            rrfScores.merge(hit.chunkId(), 1.0d / (RRF_K + index + 1), (left, right) -> left + right);
        }
    }

    private RetrievedChunkDTO withScore(RetrievedChunkDTO chunk, double score) {
        return new RetrievedChunkDTO(
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

    private String buildObservation(List<RetrievedChunkDTO> topChunks) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunkDTO topChunk : topChunks) {
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

    private List<CitationDTO> buildCitations(List<RetrievedChunkDTO> topChunks) {
        ArrayList<CitationDTO> citations = new ArrayList<>(topChunks.size());
        for (RetrievedChunkDTO topChunk : topChunks) {
            citations.add(new CitationDTO(
                    topChunk.documentId(),
                    topChunk.documentName(),
                    topChunk.chunkId(),
                    topChunk.score()
            ));
        }
        return List.copyOf(citations);
    }
}
