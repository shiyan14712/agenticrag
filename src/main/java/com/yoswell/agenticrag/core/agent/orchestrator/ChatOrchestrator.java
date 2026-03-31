package com.yoswell.agenticrag.core.agent.orchestrator;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.core.agent.ai.EnterpriseAgent;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.platform.session.service.ChatMessageService;

import dev.langchain4j.service.TokenStream;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * 核心聊天编排器
 * <p>
 * 负责将基于 WebFlux 的网络请求与 LangChain4j 的底层大模型调用（特别是 ReAct 范式的 Agent）进行桥接。
 * 它负责整个交互生命周期管理、SSE 流式事件下发、以及在 Agent 隐式调用相关 Tool 时进行上下文捕获（例如收集知识库的 Citations 溯源信息）。
 * </p>
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final EnterpriseAgent enterpriseAgent;
    private final ObjectMapper objectMapper;
    private final ChatMessageService chatMessageService;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    public ChatOrchestrator(EnterpriseAgent enterpriseAgent,
                            ObjectMapper objectMapper,
                            ChatMessageService chatMessageService,
                            RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.enterpriseAgent = enterpriseAgent;
        this.objectMapper = objectMapper;
        this.chatMessageService = chatMessageService;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    /**
     * 分发动态流式对话
     *
     * @param sessionId 当前会话的全局唯一标识
     * @param message   用户的提问内容（原始文本）
     * @return 包含增量生成文本片段、以及扩展富组件（如溯源引用数组）的响应式 SSE (Server-Sent Events) 数据流
     */
    public Flux<ServerSentEvent<String>> dispatchDynamicStream(String sessionId, String message) {
        return Flux.create(sink -> {
            // 使用虚拟线程 (Virtual Thread) 隔离底层 LLM 以及工具的同步阻塞调用，不阻塞 WebFlux Netty 核心线程池
            Thread.startVirtualThread(() -> {
                AutoCloseable retrievalScope = null;
                try {
                    // 1. 持久化用户的最新提问内容到数据库（保证上下文刷新）
                    chatMessageService.saveUserMessage(sessionId, message);

                    log.info("[Chat Orchestrator] START: Multi-agent Tool execution (ReAct)");

                    StringBuilder fullResponse = new StringBuilder();
                    
                    // 2. 绑定当前会话的检索上下文。当 Agent 利用大模型思考(Thought)并自主调用 RAG Tool(Action) 时，
                    // Tool 内部产生的富文本元数据（Citations）会自动 publish 到该 Holder 中进行跨层传递。
                    retrievalScope = ragRetrievalContextHolder.bindSession(sessionId);
                    
                    final AutoCloseable finalRetrievalScope = retrievalScope;
                    
                    // 3. 启动 LLM 的 ReAct 流程。此时控制权交接给 LangChain4j 内部机制：
                    // 模型评估是否需要调用 @Tool（比如 `RagTool` 或者 `PreferenceTool`）。
                    TokenStream tokenStream = enterpriseAgent.chat(sessionId, message);
                    tokenStream
                        .onNext(token -> {
                            // 4. 将 Agent 最终生成的增量文字，第一时间推送给前端，形成打字终端渲染效果
                            fullResponse.append(token);
                            sink.next(ServerSentEvent.builder(token).event("message").build());
                        })
                        .onComplete(response -> {
                            try {
                                // 5. Agent 回复流完全送终结束后，提取本轮可能触发知识库检索带来的 Citation (溯源引用) 并转换为 JSON 推送 UI
                                List<CitationDTO> citations = ragRetrievalContextHolder.consume(sessionId)
                                        .map(RagSearchResultDTO::citations)
                                        .orElse(List.of());
                                emitCitationsWidget(sink, citations);
                                
                                // 6. 以强一致性保存当前 Assistant 的完整回复及引用片段落等信息到 MySQL 数据库中
                                chatMessageService.saveAssistantMessage(sessionId, fullResponse.toString(), citations);
                                sink.complete();
                            } finally {
                                closeQuietly(finalRetrievalScope);
                            }
                        })
                        .onError(error -> {
                            try {
                                log.error("[Chat Orchestrator] Token stream encountered an error", error);
                                sink.error(error);
                            } finally {
                                closeQuietly(finalRetrievalScope);
                            }
                        })
                        .start();
                } catch (Exception e) {
                    log.error("[Chat Orchestrator] Error inside WebFlux Virtual Thread execution", e);
                    sink.error(e);
                    closeQuietly(retrievalScope);
                }
            });
        });
    }

    private void emitCitationsWidget(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                     List<CitationDTO> citations) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        try {
            String citationsJson = objectMapper.writeValueAsString(citations);
            sink.next(ServerSentEvent.builder(citationsJson).event("citations").build());
        } catch (Exception e) {
            log.error("[Chat Orchestrator] Error occurred while emitting citations widget", e);
        }
    }

    private void closeQuietly(AutoCloseable scope) {
        if (scope == null) {
            return;
        }
        try {
            scope.close();
        } catch (Exception e) {
            log.debug("[Chat Orchestrator] Failed to close rag retrieval scope cleanly", e);
        }
    }
}
