package com.yoswell.agenticrag.rag;

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

import com.yoswell.agenticrag.security.TenantUser;

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
            // 1. 获取安全的租户和角色上下文 (Security Filter)
            String tenantId = "default";
            String role = "ROLE_USER";
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
                tenantId = user.getTenantId();
                role = user.getRole();
            }
            
            // 2. 将提问转换为向量
            Embedding queryVector = embeddingModel.embed(query).content();

            // 3. 构建混合检索请求 (基于 ES 8.x) - 具体使用时请配合实际数据结构处理
            // 这里因为没建具体的 ES Index结构，通过代码展示逻辑流程：
            // Filter: { "term": { "tenant_id": tenantId }, "term": { "allowed_roles": role } }
            // BM25 Query: { "match": { "content": query } }
            // Knn Query: { "field": "vector", "query_vector": queryVector.vectorAsList(), "k": knnTopK, "filter": [...] }
            log.debug("Building Hybrid Search Request for Tenant: {}, Role: {}", tenantId, role);

            // 模拟两路召回结果打分 (Reciprocal Rank Fusion - RRF)
            // 实际上 ES 8.4+ 原生支持 RRF 查询，若支持则可直接返回。
            // 这里演示如何在 Java 侧做 RRF 融合。
            Map<String, Double> rrfScores = calculateMockRrfFusion();

            // 4. 重排序 (Rerank)
            // 将 topN 发送至 ScoringModel(BGE-Reranker) 交叉打分重排
            List<String> topChunks = crossAttentionRerank(new ArrayList<>(rrfScores.keySet()), query)
                    .stream()
                    .limit(rerankTopN)
                    .toList();
            
            log.info("Rag search completed, returning top {} chunks.", topChunks.size());
            
            // 5. 拼接组装最终带有 Source 文档标记的 Context
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
    
    // 模拟 RRF 算法
    private Map<String, Double> calculateMockRrfFusion() {
        Map<String, Double> rrfMap = new HashMap<>();
        int RRF_K = 60;
        // 模拟召回两个列表，对它们的 Rank 计算: score = 1 / (k + rank)
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
    
    // 模拟 Reranker API 交互（真实情况应接 dev.langchain4j.model.scoring.ScoringModel）
    private List<String> crossAttentionRerank(List<String> rrfSortedDocs, String query) {
        log.debug("Reranking {} documents against query...", rrfSortedDocs.size());
        // Mock 真实重排后的具体文本块
        return rrfSortedDocs.stream()
                .map(docId -> "This is a detailed excerpt from " + docId + " related to: " + query)
                .collect(Collectors.toList());
    }
}
