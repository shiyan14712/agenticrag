package com.yoswell.agenticrag.retrieval.document.mq;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
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

    public DocumentMessageListener(ObjectMapper objectMapper,
                                   DocumentVectorizationService documentVectorizationService) {
        this.objectMapper = objectMapper;
        this.documentVectorizationService = documentVectorizationService;
    }

    /**
     * 处理“文档进入向量化阶段”的消息。
     *
     * @param message Kafka 中的 JSON 字符串消息体
     */
    @KafkaListener(topics = "doc-vectorize-request", groupId = "agenticrag-group")
    public void listenVectorizeRequest(String message) {
        log.info("Received doc-vectorize-request from Kafka: {}", message);
        try {
            DocumentVectorizeRequestDTO request = objectMapper.readValue(message, DocumentVectorizeRequestDTO.class);
            documentVectorizationService.vectorize(request);
        } catch (Exception exception) {
            log.error("Failed to process doc-vectorize-request", exception);
            throw new RuntimeException("doc-vectorize-request processing failed", exception);
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
        log.error("Received failed document processing from doc-dlq: {}", message);
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            Object documentId = payload.get("documentId");
            if (documentId != null) {
                documentVectorizationService.markFailed(documentId.toString());
            }
        } catch (Exception exception) {
            log.warn("Failed to parse doc-dlq payload for status update", exception);
        }
    }
}
