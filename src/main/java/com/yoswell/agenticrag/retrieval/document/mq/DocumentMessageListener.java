package com.yoswell.agenticrag.retrieval.document.mq;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.service.DocumentVectorizationService;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkIndexService;

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
    private final DocumentKafkaProperties kafkaProperties;

    public DocumentMessageListener(ObjectMapper objectMapper,
                                   DocumentVectorizationService documentVectorizationService,
                                   KnowledgeChunkIndexService knowledgeChunkIndexService,
                                   DocumentKafkaProperties kafkaProperties) {
        this.objectMapper = objectMapper;
        this.documentVectorizationService = documentVectorizationService;
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * 处理“文档进入向量化阶段”的消息。
     *
     * @param message Kafka 中的 JSON 字符串消息体
     */
    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.vectorizeRequest}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    public void listenVectorizeRequest(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.info("[Offline RAG][VECTORIZE_CONSUMER] 收到 Kafka 消息: topic={}, partition={}, offset={}, key={}, payloadSize={} chars",
                record.topic(), record.partition(), record.offset(), record.key(), message.length());
        try {
            DocumentVectorizeRequestDTO request = objectMapper.readValue(message, DocumentVectorizeRequestDTO.class);
            log.info("[Offline RAG][VECTORIZE_CONSUMER] 消息解析完成: documentId={}, tenantId={}, kbId={}, fileName={}",
                    request.documentId(), request.tenantId(), request.kbId(), request.fileName());
            documentVectorizationService.vectorize(request);
            log.info("[Offline RAG][VECTORIZE_CONSUMER] 向量化流程执行完成: documentId={}", request.documentId());
            acknowledgment.acknowledge();
        } catch (Exception exception) {
            log.error("[Offline RAG][VECTORIZE_CONSUMER] Kafka 消息处理失败: topic={}, key={}",
                    record.topic(), record.key(), exception);
            throw new RuntimeException("document vectorize processing failed", exception);
        }
    }

    /**
     * 处理“文档删除”请求，清理 Elasticsearch 中的相关向量块。
     *
     * @param message Kafka 中的 JSON 字符串消息体
     */
    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.deleteRequest}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    public void listenDocumentDeleteRequest(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.info("[Offline RAG][DELETE_CONSUMER] 收到 Kafka 消息: topic={}, partition={}, offset={}, key={}, payloadSize={} chars",
                record.topic(), record.partition(), record.offset(), record.key(), message.length());
        try {
            DocumentDeleteRequestDTO request = objectMapper.readValue(message, DocumentDeleteRequestDTO.class);
            knowledgeChunkIndexService.deleteByDocumentId(request.documentId(), request.tenantId());
            acknowledgment.acknowledge();
        } catch (Exception exception) {
            log.error("[Offline RAG][DELETE_CONSUMER] Failed to process Kafka delete message: topic={}, key={}",
                    record.topic(), record.key(), exception);
            throw new RuntimeException("document delete processing failed", exception);
        }
    }

    /**
     * 处理死信队列中的失败消息，并尽量把文档状态回写为失败。
     *
     * @param message 死信消息体
     */
    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.deadLetter}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    @SuppressWarnings("unchecked")
    public void listenDeadLetterQueue(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.error("[Offline RAG][DLQ_CONSUMER] 收到死信消息: topic={}, sourceKey={}, payload={}",
                kafkaProperties.getTopics().getDeadLetter(), record.key(), message);
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            Object documentId = payload.get("documentId");
            if (documentId != null) {
                documentVectorizationService.markFailed(documentId.toString());
            }
        } catch (Exception exception) {
            log.warn("[Offline RAG][DLQ_CONSUMER] Failed to parse dead-letter payload for status update", exception);
        } finally {
            acknowledgment.acknowledge();
        }
    }
}
