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
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeSearchService;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

@Component
public class RagTool {

    private static final Logger log = LoggerFactory.getLogger(RagTool.class);
    private static final int RRF_K = 60;

    private final KnowledgeSearchService knowledgeSearchService;
    private final EmbeddingModel embeddingModel;
    private final RerankerClient rerankerClient;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    @Value("${rag.retrieval.knn-top-k:20}")
    private int knnTopK;

    @Value("${rag.retrieval.bm25-top-k:20}")
    private int bm25TopK;

    @Value("${rag.retrieval.rerank-top-n:5}")
    private int rerankTopN;

    public RagTool(KnowledgeSearchService knowledgeSearchService,
                   EmbeddingModel embeddingModel,
                   RerankerClient rerankerClient,
                   RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.embeddingModel = embeddingModel;
        this.rerankerClient = rerankerClient;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    @Tool("search_enterprise_knowledge")
    public String searchEnterpriseKnowledge(String query) {
        log.info("[RAG TOOL] LLM 已决策调用工具 search_enterprise_knowledge，开始企业知识检索。queryPreview={}", summarizeQuery(query));
        long totalStartTime = System.currentTimeMillis();
        try {
            TenantUser user = resolveCurrentTenantUser();

            if (user == null) {
                log.warn("[RAG TOOL] 鉴权失败，未找到有效的租户用户身份。threadName={}, threadId={}",
                        Thread.currentThread().getName(), Thread.currentThread().threadId());
                throw new BusinessException(ErrorCode.UNAUTHORIZED_ERROR);
            }
            
            // 优先使用鉴权租户标识执行隔离过滤；若历史令牌缺失 tenantId，则兼容回退到 userId。
            String tenantId = resolveTenantIsolationId(user);
            String role = user.getRole();

            log.info("[RAG TOOL] 知识检索鉴权通过, tenantId={}, query={}", tenantId, query);
            
            Embedding queryVector = embeddingModel.embed(query).content();
            List<String> allowedRoles = List.of(role);
            log.debug("[RAG TOOL] 准备执行多路大模型混合检索, tenant={}, roles={}", tenantId, allowedRoles);

            // 第一阶段：多路归召（BM25 关键字 + KNN 向量检索）
            long retrieveStartTime = System.currentTimeMillis();
            List<RetrievedChunkDTO> bm25Hits = knowledgeSearchService.searchByKeyword(query, tenantId, allowedRoles, bm25TopK);
            List<RetrievedChunkDTO> knnHits = knowledgeSearchService.searchByVector(queryVector.vectorAsList(), tenantId, allowedRoles, knnTopK);
            long retrieveCostTime = System.currentTimeMillis() - retrieveStartTime;

            if (bm25Hits.isEmpty() && knnHits.isEmpty()) {
                log.warn("[RAG TOOL] 双路检索均未命中候选。tenantId={}, role={}, queryPreview={}", tenantId, role, summarizeQuery(query));
            }
            
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

    private TenantUser resolveCurrentTenantUser() {
        TenantUser sessionBoundUser = ragRetrievalContextHolder.currentTenantUser().orElse(null);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        TenantUser securityContextUser = null;
        if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
            securityContextUser = user;
        }

        if (sessionBoundUser != null) {
            if (securityContextUser != null && !sameIdentity(sessionBoundUser, securityContextUser)) {
                log.warn("[RAG TOOL] 鉴权上下文冲突，拒绝执行。sessionUserId={}, securityUserId={}, threadName={}, threadId={}",
                        sessionBoundUser.getUserId(), securityContextUser.getUserId(),
                        Thread.currentThread().getName(), Thread.currentThread().threadId());
                throw new BusinessException(ErrorCode.UNAUTHORIZED_ERROR);
            }
            return sessionBoundUser;
        }

        if (securityContextUser != null) {
            return securityContextUser;
        }

        log.warn("[RAG TOOL] 权限缺失或无效，未找到可用租户身份。threadName={}, threadId={}",
                Thread.currentThread().getName(), Thread.currentThread().threadId());
        throw new BusinessException(ErrorCode.UNAUTHORIZED_ERROR);
    }

    private boolean sameIdentity(TenantUser left, TenantUser right) {
        return left.getUserId().equals(right.getUserId())
                && left.getTenantId().equals(right.getTenantId());
    }

    private String resolveTenantIsolationId(TenantUser user) {
        if (user.getTenantId() != null && !user.getTenantId().isBlank()) {
            return user.getTenantId();
        }
        log.warn("[RAG TOOL] 当前鉴权主体缺少 tenantId，回退使用 userId 执行隔离过滤。userId={}", user.getUserId());
        return user.getUserId();
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
