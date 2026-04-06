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

import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
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
        long totalStartTime = System.currentTimeMillis();
        try {
            // 获取当前线程的安全上下文。得益于 SecurityConfig 中开启的 MODE_INHERITABLETHREADLOCAL，
            // 异步或子线程调用（如 LangChain4j Worker）中依然可以获取到登录阶段写入的凭证。
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof TenantUser user)) {
                log.warn("[RAG TOOL] 权限缺失或无效，安全上下文为空或未包含预期租户信息");
                throw new BusinessException(ErrorCode.UNAUTHORIZED_ERROR);
            }
            
            // 按照设计要求：本系统中用户ID即为租户级别的隔离单元，确保数据严格隔离（Zero-Trust Tenant Isolation）
            String tenantId = user.getUserId();
            String role = user.getRole();

            log.info("[RAG TOOL] 知识检索鉴权通过, tenantId={}, query={}", tenantId, query);
            
            Embedding queryVector = embeddingModel.embed(query).content();
            List<String> allowedRoles = List.of(role);
            log.debug("[RAG TOOL] 准备执行多路大模型混合检索, tenant={}, roles={}", tenantId, allowedRoles);

            // 第一阶段：多路归召（BM25 关键字 + KNN 向量检索）
            long retrieveStartTime = System.currentTimeMillis();
            List<RetrievedChunkDTO> bm25Hits = knowledgeChunkIndexService.searchByKeyword(query, tenantId, allowedRoles, bm25TopK);
            List<RetrievedChunkDTO> knnHits = knowledgeChunkIndexService.searchByVector(queryVector.vectorAsList(), tenantId, allowedRoles, knnTopK);
            long retrieveCostTime = System.currentTimeMillis() - retrieveStartTime;
            
            // 第二阶段：倒排融合（Reciprocal Rank Fusion），合并多路召回的结果列表
            List<RetrievedChunkDTO> fusedChunks = calculateRrfFusion(bm25Hits, knnHits);
            log.info("[RAG TOOL] 多路召回与融合完成, 耗时 {}ms (bm25={}, knn={}, fused={})", 
                    retrieveCostTime, bm25Hits.size(), knnHits.size(), fusedChunks.size());
            
            // 第三阶段：调用大模型做交叉打分和倒排（Cross-Attention 重排）
            long rerankStartTime = System.currentTimeMillis();
            List<RetrievedChunkDTO> rerankedChunks = crossAttentionRerank(fusedChunks, query);
            long rerankCostTime = System.currentTimeMillis() - rerankStartTime;
            log.info("[RAG TOOL] Cross-Attention 重排完成, 耗时 {}ms", rerankCostTime);
            
            // 截断获取排名靠前的片段
            List<RetrievedChunkDTO> topChunks = rerankedChunks.stream().limit(rerankTopN).toList();

            // 将片段封装为含有引用的回答并上下文留存
            RagSearchResultDTO result = new RagSearchResultDTO(
                    buildObservation(topChunks),
                    topChunks,
                    buildCitations(topChunks)
            );
            
            // 将检索结果和引用信息发布至上下文，便于外层 SSE Stream 返回溯源给前端显示
            ragRetrievalContextHolder.publish(result);
            
            long totalCostTime = System.currentTimeMillis() - totalStartTime;
            log.info("[RAG TOOL] 工具执行完成并发布溯源，耗时总计 {}ms 提取前 {} 条知识。", totalCostTime, topChunks.size());
            return result.observation();
            
        } catch (BusinessException businessEx) {
            // 直接抛出业务级异常，避免被底层 RuntimeException 包裹，以便 GlobalExceptionHandler 可精准拦截响应给端侧
            log.error("[RAG TOOL] 知识检索发生业务级校验异常: {}", businessEx.getMessage());
            throw businessEx;
            
        } catch (Exception exception) {
            // 将底层未指定的各种抛出明确包装成 BusinessException SYSTEM_ERROR 类型，保证抛栈链路结构对监控预警方一致友好
            log.error("[RAG TOOL] 检索期间发生底层的未知系统异常，即将包裹为业务异常暴露", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "知识检索失败：" + exception.getMessage());
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
