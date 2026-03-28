package com.yoswell.agenticrag.core.agent.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.web.security.model.TenantUser;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

@Component
public class RagTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);

    private final ElasticsearchClient elasticsearchClient;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.retrieval.knn-top-k:20}")
    private int knnTopK;

    @Value("${rag.retrieval.bm25-top-k:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.rerank-top-n:5}")
    private int rerankTopN;

    public RagTool(ElasticsearchClient elasticsearchClient, EmbeddingModel embeddingModel) {
        this.elasticsearchClient = elasticsearchClient;
        this.embeddingModel = embeddingModel;
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

            log.debug("Building Hybrid Search Request for Tenant: {}, Role: {}", tenantId, role);

            Map<String, Double> rrfScores = calculateMockRrfFusion();

            List<String> topChunks = crossAttentionRerank(new ArrayList<>(rrfScores.keySet()), query)
                    .stream()
                    .limit(rerankTopN)
                    .toList(); 
            
            log.info("Rag search completed, returning top {} chunks.", topChunks.size());
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < topChunks.size(); i++) {
                sb.append("[Doc ID: chunk-").append(i).append("] ")
                  .append(topChunks.get(i))
                  .append("\n\n");
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("Error during enterprise knowledge search", e);
            throw new RuntimeException("Search failed", e);
        }
    }
    

    // TODO: actual BM25 and KNN search against Elasticsearch, this is just a mock implementation to demonstrate the RRF fusion and reranking logic
    private Map<String, Double> calculateMockRrfFusion() {
        Map<String, Double> rrfMap = new HashMap<>();
        int RRF_K = 60;
        String[] bm25Hits = {"docA", "docB", "docC"};
        String[] knnHits = {"docB", "docD", "docA"};
        
        for (int i = 0; i < bm25Hits.length; i++) {
            rrfMap.put(bm25Hits[i], rrfMap.getOrDefault(bm25Hits[i], 0.0) + 1.0 / (RRF_K + i + 1));
        }
        for (int i = 0; i < knnHits.length; i++) {
            rrfMap.put(knnHits[i], rrfMap.getOrDefault(knnHits[i], 0.0) + 1.0 / (RRF_K + i + 1));
        }
        return rrfMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }
    
    // TODO: actual cross-attention based reranking via reranker endpoint, this is just a mock implementation to demonstrate the concept
    private List<String> crossAttentionRerank(List<String> rrfSortedDocs, String query) {
        log.debug("Reranking {} documents against query...", rrfSortedDocs.size());
        return rrfSortedDocs.stream()
                .map(docId -> "This is a detailed excerpt from " + docId + " related to: " + query)
                .collect(Collectors.toList());
    }
}
