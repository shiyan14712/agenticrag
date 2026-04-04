package com.yoswell.agenticrag.retrieval.document.mq;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkIndexService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentVectorizationService;

import tools.jackson.databind.ObjectMapper;

@Service
/**
 * 消费文档处理相关 Kafka 消息的入口。
 *
 * <p>监听器本身只负责边界转换和兜底日志，具体业务交由服务层执行。</p>
 */
public class DocumentMessageListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageListener.class);

    private final ObjectMapper objectMapper;
    private final DocumentVectorizationService documentVectorizationService;
    private final KnowledgeChunkIndexService knowledgeChunkIndexService;

    public DocumentMessageListener(ObjectMapper objectMapper,
                                   DocumentVectorizationService documentVectorizationService,
                                   KnowledgeChunkIndexService knowledgeChunkIndexService) {
        this.objectMapper = objectMapper;
        this.documentVectorizationService = documentVectorizationService;
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
    }

    /**
     * 处理“文档进入向量化阶段”的消息。
     *
     * @param message Kafka 中的 JSON 字符串消息体
     */
    @KafkaListener(topics = "doc-vectorize-request", groupId = "agenticrag-group")
    public void listenVectorizeRequest(String message) {
        log.info("[Offline RAG][VECTORIZE_CONSUMER] 收到 doc-vectorize-request 消息，开始解析。payloadSize={} chars", message.length());
        try {
            DocumentVectorizeRequestDTO request = objectMapper.readValue(message, DocumentVectorizeRequestDTO.class);
            log.info("[Offline RAG][VECTORIZE_CONSUMER] 消息解析完成: documentId={}, tenantId={}, kbId={}, fileName={}",
                    request.documentId(), request.tenantId(), request.kbId(), request.fileName());
            documentVectorizationService.vectorize(request);
            log.info("[Offline RAG][VECTORIZE_CONSUMER] 向量化流程执行完成: documentId={}", request.documentId());
        } catch (Exception exception) {
            log.error("[Offline RAG][VECTORIZE_CONSUMER] doc-vectorize-request 处理失败", exception);
            throw new RuntimeException("doc-vectorize-request processing failed", exception);
        }
    }

    /**
     * 处理“文档删除”请求，清理 Elasticsearch 中的相关向量块。
     *
     * @param message Kafka 中的 JSON 字符串消息体
     */
    @KafkaListener(topics = "doc-delete-request", groupId = "agenticrag-group")
    public void listenDocumentDeleteRequest(String message) {
        log.info("[Offline RAG][DELETE_CONSUMER] 收到 doc-delete-request 消息，开始解析。payloadSize={} chars", message.length());
        try {
            DocumentDeleteRequestDTO request = objectMapper.readValue(message, DocumentDeleteRequestDTO.class);
            knowledgeChunkIndexService.deleteByDocumentId(request.documentId(), request.tenantId());
        } catch (Exception exception) {
            log.error("[Offline RAG][DELETE_CONSUMER] Failed to process doc-delete-request", exception);
            throw new RuntimeException("doc-delete-request processing failed", exception);
        }
    }

    /**
     * 处理死信队列中的失败消息，并尽量把文档状态回写为失败。
     *
     * @param message 死信消息体
     */
    @KafkaListener(topics = "doc-dlq", groupId = "agenticrag-group")
    @SuppressWarnings("unchecked")
    public void listenDeadLetterQueue(String message) {
        log.error("[Offline RAG][DLQ_CONSUMER] 从 doc-dlq 收到失败的文档处理消息: {}", message);
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            Object documentId = payload.get("documentId");
            if (documentId != null) {
                documentVectorizationService.markFailed(documentId.toString());
            }
        } catch (Exception exception) {
            log.warn("[Offline RAG][DLQ_CONSUMER] Failed to parse doc-dlq payload for status update", exception);
        }
    }
}
