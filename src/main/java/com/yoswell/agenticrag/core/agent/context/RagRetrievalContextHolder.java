package com.yoswell.agenticrag.core.agent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;
import com.yoswell.agenticrag.web.security.model.TenantUser;

/**
 * RAG 检索上下文聚合器 —— 在 ReAct 生命周期内合并多次 Tool 调用的结构化检索结果
 *
 * <p>核心职责：
 * 将一次回答过程里多次 {@code search_enterprise_knowledge} 的检索结果按会话维度聚合，
 * 在流式结束时统一输出 citations，避免前端只看到最后一次工具调用的溯源</p>
 *
 * <p>线程模型约束（关键）：
 * LangChain4j 的 Tool 执行线程与调度线程可能不同，
 * {@code InheritableThreadLocal} 无法保证跨线程池传播
 * 因此本类使用显式映射：{@code threadId -> sessionId}，
 * 并在编排器回调阶段注册当前线程与会话绑定</p>
 *
 * <p>典型时序：</p>
 * <pre>
 *   dispatchDynamicStream 开始
 *     -> bindCurrentThread(sessionId)
 *   TokenStream 回调触发
 *     -> registerCurrentThread(sessionId)
 *   RagTool.publish(result)
 *     -> 通过当前 threadId 找到 sessionId 并 merge
 *   onComplete/onError
 *     -> consume(sessionId) / clearSessionBindings(sessionId)
 * </pre>
 */
@Component
public class RagRetrievalContextHolder {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalContextHolder.class);

    /** 线程到会话的快速映射：用于 Tool 执行时反查当前 sessionId */
    private final Map<Long, String> threadToSession = new ConcurrentHashMap<>();

    /** 会话到线程集合的反向索引：用于回答结束后批量清理绑定，避免线程池复用污染 */
    private final Map<String, Set<Long>> sessionToThreads = new ConcurrentHashMap<>();

    /** 会话级聚合结果：支持一次回答里多次 RAG 调用的 citations 合并 */
    private final Map<String, RagSearchResultDTO> sessionResults = new ConcurrentHashMap<>();

    /** 会话级认证主体快照：用于跨线程池执行 Tool 时恢复可靠用户身份 */
    private final Map<String, TenantUser> sessionPrincipals = new ConcurrentHashMap<>();

    // ================================================================
    // 线程与会话绑定管理
    // ================================================================

    /**
     * 将当前线程绑定到给定会话，并返回可关闭作用域
     *
     * <p>用于编排器入口线程的绑定，保证启动阶段也具备正确上下文</p>
     */
    public AutoCloseable bindCurrentThread(String sessionId) {
        registerCurrentThread(sessionId);
        return this::unregisterCurrentThread;
    }

    /**
     * 在当前线程注册 session 绑定
     *
     * <p>建议在 TokenStream 各回调起始处调用，确保回调线程与 Tool 线程之间
     * 的上下文可追踪</p>
     */
    public void registerCurrentThread(String sessionId) {
        long threadId = Thread.currentThread().threadId();
        String previousSessionId = threadToSession.put(threadId, sessionId);

        if (previousSessionId != null && !previousSessionId.equals(sessionId)) {
            removeFromSessionThreads(previousSessionId, threadId);
        }
        sessionToThreads.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(threadId);
    }

    /** 注销当前线程绑定（通常用于作用域关闭） */
    public void unregisterCurrentThread() {
        long threadId = Thread.currentThread().threadId();
        String sessionId = threadToSession.remove(threadId);
        if (sessionId != null) {
            removeFromSessionThreads(sessionId, threadId);
        }
    }

    /**
     * 清理一个会话关联的全部线程绑定
     *
     * <p>在回答完成/失败时调用，防止线程池复用导致后续请求串会话</p>
     */
    public void clearSessionBindings(String sessionId) {
        Set<Long> threadIds = sessionToThreads.remove(sessionId);
        sessionPrincipals.remove(sessionId);
        if (threadIds == null || threadIds.isEmpty()) {
            return;
        }
        for (Long threadId : threadIds) {
            threadToSession.remove(threadId, sessionId);
        }
    }

    /**
     * 注册会话对应的认证主体快照
     */
    public void registerSessionPrincipal(String sessionId, TenantUser tenantUser) {
        if (sessionId == null || sessionId.isBlank() || tenantUser == null) {
            return;
        }
        sessionPrincipals.put(sessionId, tenantUser);
    }

    /**
     * 按当前线程绑定反查会话主体
     */
    public Optional<TenantUser> currentTenantUser() {
        String sessionId = threadToSession.get(Thread.currentThread().threadId());
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionPrincipals.get(sessionId));
    }

    // ================================================================
    // 检索结果聚合管理
    // ================================================================

    /**
     * 发布一次 RAG 检索结果并按会话聚合
     *
     * <p>若当前线程未绑定会话，将记录警告并丢弃本次结果，避免错误写入其他会话</p>
     */
    public void publish(RagSearchResultDTO result) {
        String sessionId = threadToSession.get(Thread.currentThread().threadId());
        if (sessionId == null) {
            log.warn("[RAG CONTEXT] publish ignored because current thread has no bound session. threadId={}, threadName={}",
                    Thread.currentThread().threadId(), Thread.currentThread().getName());
            return;
        }

        sessionResults.merge(sessionId, result, this::mergeResults);
    }

    /** 消费并移除会话聚合结果（通常在 SSE 完成时调用） */
    public Optional<RagSearchResultDTO> consume(String sessionId) {
        return Optional.ofNullable(sessionResults.remove(sessionId));
    }

    /** 清理会话聚合结果（通常在异常终止时调用） */
    public void clearSessionResult(String sessionId) {
        sessionResults.remove(sessionId);
    }

    /** 合并多次 RAG 调用结果：按 chunkId 去重，后到结果覆盖 observation */
    private RagSearchResultDTO mergeResults(RagSearchResultDTO current, RagSearchResultDTO incoming) {
        Map<String, RetrievedChunkDTO> mergedChunks = new LinkedHashMap<>();
        current.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));
        incoming.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));

        Map<String, CitationDTO> mergedCitations = new LinkedHashMap<>();
        current.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));
        incoming.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));

        return new RagSearchResultDTO(
                incoming.observation(),
                List.copyOf(new ArrayList<>(mergedChunks.values())),
                List.copyOf(new ArrayList<>(mergedCitations.values())));
    }

    private void removeFromSessionThreads(String sessionId, long threadId) {
        Set<Long> threadIds = sessionToThreads.get(sessionId);
        if (threadIds == null) {
            return;
        }
        threadIds.remove(threadId);
        if (threadIds.isEmpty()) {
            sessionToThreads.remove(sessionId, threadIds);
        }
    }
}
