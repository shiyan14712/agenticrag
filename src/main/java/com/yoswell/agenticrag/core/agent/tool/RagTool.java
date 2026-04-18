package com.yoswell.agenticrag.core.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.alibaba.ttl.TtlRunnable;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.constants.ToolExecutionConstants;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.core.agent.rag.RerankerClient;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeSearchService;
import com.yoswell.agenticrag.web.security.context.TenantContextHolder;
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

    @Value("${rag.retrieval.final-score-threshold:0.0}")
    private double finalScoreThreshold;

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
            // 校验查询参数
            if (query == null || query.trim().isEmpty()) {
                log.warn("[RAG TOOL] 查询参数为空，无法执行检索。");
                return ToolExecutionConstants.markFailed("查询参数不能为空");
            }
            
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

            // 第一阶段：多路召回（BM25 关键字 + KNN 向量检索，虚拟线程并行执行）
            long retrieveStartTime = System.currentTimeMillis();
            FutureTask<List<RetrievedChunkDTO>> bm25Task = new FutureTask<>(
                () -> knowledgeSearchService.searchByKeyword(query, tenantId, allowedRoles, bm25TopK));
            FutureTask<List<RetrievedChunkDTO>> knnTask = new FutureTask<>(
                () -> knowledgeSearchService.searchByVector(queryVector.vectorAsList(), tenantId, allowedRoles, knnTopK));

            // 使用虚拟线程并发执行双路检索，降低端到端检索耗时
            Thread.ofVirtual().name("rag-bm25[" + tenantId + "]").start(TtlRunnable.get(bm25Task));
            Thread.ofVirtual().name("rag-knn[" + tenantId + "]").start(TtlRunnable.get(knnTask));

            List<RetrievedChunkDTO> bm25Hits = awaitRetrievalResult("bm25", bm25Task, knnTask);
            List<RetrievedChunkDTO> knnHits = awaitRetrievalResult("knn", knnTask, bm25Task);
            long retrieveCostTime = System.currentTimeMillis() - retrieveStartTime;

            if (bm25Hits.isEmpty()) {
                log.warn("[RAG TOOL] BM25检索未命中候选。tenantId={}, role={}, queryPreview={}", tenantId, role, summarizeQuery(query));
            }
            if (knnHits.isEmpty()) {
                log.warn("[RAG TOOL] KNN检索未命中候选。tenantId={}, role={}, queryPreview={}", tenantId, role, summarizeQuery(query));
            }
            
            // 第二阶段：倒排融合（Reciprocal Rank Fusion）
            // 仅使用各通道 rank(d)=index+1，不使用 BM25/KNN 原始 score，避免量纲不一致导致混算
            List<RetrievedChunkDTO> fusedChunks = calculateRrfFusion(bm25Hits, knnHits);
            log.info("[RAG TOOL] 多路召回与融合完成, 耗时 {}ms (bm25={}, knn={}, fused={})", 
                    retrieveCostTime, bm25Hits.size(), knnHits.size(), fusedChunks.size());
            
            // 第三阶段：调用大模型做交叉打分和倒排（Cross-Attention 重排）
            long rerankStartTime = System.currentTimeMillis();
            RerankerClient.RerankOutcome rerankOutcome = crossAttentionRerank(fusedChunks, query);
            List<RetrievedChunkDTO> rerankedChunks = rerankOutcome.chunks();
            List<RetrievedChunkDTO> thresholdedChunks = applyFinalScoreThreshold(rerankedChunks, rerankOutcome, tenantId, role, query);
            long rerankCostTime = System.currentTimeMillis() - rerankStartTime;
            if (rerankOutcome.fallbackApplied()) {
                log.warn("[RAG TOOL] reranker 降级回退生效，继续使用 RRF 顺序。reason={}, elapsed={}ms",
                        rerankOutcome.fallbackReason(), rerankCostTime);
            } else {
                log.info("[RAG TOOL] Cross-Attention 重排完成, 耗时 {}ms", rerankCostTime);
            }
            
            // 先按最终分数阈值强制裁剪，再截断获取排名靠前的片段
            List<RetrievedChunkDTO> topChunks = thresholdedChunks.stream().limit(rerankTopN).toList();

            String observation = buildObservation(topChunks, rerankOutcome);

            // 将片段封装为含有引用的回答并上下文留存
            RagSearchResultDTO result = new RagSearchResultDTO(
                    observation,
                    topChunks,
                    buildCitations(topChunks)
            );
            
            // 将检索结果和引用信息发布至上下文，便于外层 SSE Stream 返回溯源给前端显示
            ragRetrievalContextHolder.publish(result);
            
            long totalCostTime = System.currentTimeMillis() - totalStartTime;
            log.info("[RAG TOOL] 工具执行完成并发布溯源，耗时总计 {}ms 提取前 {} 条知识。", totalCostTime, topChunks.size());
            return result.observation();
            
        } catch (BusinessException businessEx) {
            // Tool 层失败通过 marker 回传，避免中断整个 Agent Loop。
            log.error("[RAG TOOL] 知识检索发生业务级校验异常，将回传 tool failed 状态: {}", businessEx.getMessage());
            return ToolExecutionConstants.markFailed("知识检索失败：" + businessEx.getMessage());
            
        } catch (Exception exception) {
            log.error("[RAG TOOL] 检索期间发生底层未知异常，将回传 tool failed 状态", exception);
            return ToolExecutionConstants.markFailed("知识检索失败：" + exception.getMessage());
        }
    }

    private TenantUser resolveCurrentTenantUser() {
        // 1️⃣ 优先使用会话级快照（由 ChatOrchestrator 在 ReAct 循环入口注册）
        TenantUser sessionBoundUser = ragRetrievalContextHolder.currentTenantUser().orElse(null);

        // 2️⃣ Spring SecurityContext（在请求主线程上有效，子线程可能为空）
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        TenantUser securityContextUser = null;
        if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
            securityContextUser = user;
        }

        // 3️⃣ TTL 传播层（子虚拟线程 / 线程池任务内的安全兼容层）
        TenantUser ttlUser = TenantContextHolder.get();

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

        if (ttlUser != null) {
            log.debug("[RAG TOOL] Resolved TenantUser via TenantContextHolder (TTL): userId={}", ttlUser.getUserId());
            return ttlUser;
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

    /**
     * 等待检索任务结果；若当前通道失败，会取消另一通道任务以减少无效资源占用。
     */
    private List<RetrievedChunkDTO> awaitRetrievalResult(
            String channel,
            FutureTask<List<RetrievedChunkDTO>> currentTask,
            FutureTask<List<RetrievedChunkDTO>> siblingTask) {
        try {
            return currentTask.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            siblingTask.cancel(true);
            log.warn("[RAG TOOL] 等待 {} 检索结果时线程被中断，已取消另一通道任务", channel, exception);
            throw new RuntimeException("等待 " + channel + " 检索结果时线程被中断", exception);
        } catch (ExecutionException exception) {
            siblingTask.cancel(true);
            Throwable cause = exception.getCause();
            log.error("[RAG TOOL] {} 检索任务执行失败，已取消另一通道任务", channel, cause);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("等待 " + channel + " 检索结果失败", cause);
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

    RerankerClient.RerankOutcome crossAttentionRerank(List<RetrievedChunkDTO> fusedChunks, String query) {
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
     * 注意：该计算仅依赖列表顺序（rank），不会使用 hits 中的 score 字段，
     * 以避免 BM25/KNN 不同量纲分数的直接混算。
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
            int rank = index + 1;
            // RRF 严格按 rank 贡献值融合，通道原始 score 仅保留为检索痕迹，不参与融合计算。
            double rankContribution = 1.0d / (RRF_K + rank);
            rrfScores.merge(hit.chunkId(), rankContribution, (left, right) -> left + right);
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

    private List<RetrievedChunkDTO> applyFinalScoreThreshold(List<RetrievedChunkDTO> chunks,
                                                             RerankerClient.RerankOutcome rerankOutcome,
                                                             String tenantId,
                                                             String role,
                                                             String query) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        double normalizedThreshold = normalizeFinalScoreThreshold();
        if (normalizedThreshold <= 0d) {
            return chunks;
        }

        List<RetrievedChunkDTO> filteredChunks = chunks.stream()
                .filter(chunk -> chunk.score() >= normalizedThreshold)
                .toList();

        int removedCount = chunks.size() - filteredChunks.size();
        if (removedCount > 0) {
            log.info("[RAG TOOL] 最终分数阈值过滤完成。threshold={}, source={}, before={}, after={}, removed={} ",
                    normalizedThreshold,
                    rerankOutcome.fallbackApplied() ? "rrf" : "reranker",
                    chunks.size(),
                    filteredChunks.size(),
                    removedCount);
        }

        if (filteredChunks.isEmpty()) {
            log.warn("[RAG TOOL] 候选片段全部低于最终分数阈值，已强制舍弃。threshold={}, tenantId={}, role={}, queryPreview={}",
                    normalizedThreshold, tenantId, role, summarizeQuery(query));
        }

        return filteredChunks;
    }

    private double normalizeFinalScoreThreshold() {
        if (finalScoreThreshold < 0d) {
            log.warn("[RAG TOOL] 检测到非法 final-score-threshold={}，已回退为 0.0", finalScoreThreshold);
            return 0d;
        }
        return finalScoreThreshold;
    }

    private String buildObservation(List<RetrievedChunkDTO> topChunks, RerankerClient.RerankOutcome rerankOutcome) {
        StringBuilder builder = new StringBuilder(buildToolResultSummary(topChunks.size(), rerankOutcome));
        builder.append("\n");
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

    private String buildToolResultSummary(int topChunkCount, RerankerClient.RerankOutcome rerankOutcome) {
        if (rerankOutcome.fallbackApplied()) {
            return "检索到 " + topChunkCount + " 条知识片段（reranker 不可用，已回退到 RRF 排序）";
        }
        return "检索到 " + topChunkCount + " 条知识片段（已完成 reranker 重排）";
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
