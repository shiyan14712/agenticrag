package com.yoswell.agenticrag.retrieval.document.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
/**
 * 负责向文档处理相关 Kafka 主题投递消息。
 */
public class DocumentMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentKafkaProperties kafkaProperties;

    public DocumentMessageProducer(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   DocumentKafkaProperties kafkaProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * 发送文档解析请求。
     *
     * @param request 解析阶段所需的上下文
     */
    public void sendDocParseRequest(DocumentParseRequestDTO request) {
        String topic = kafkaProperties.getTopics().getParseRequest();
        log.info("[Upload Pipeline][DISPATCH] 开始发送 Kafka 消息: topic={}, documentId={}, tenantId={}, kbId={}, fileExtension={}",
                topic, request.documentId(), request.tenantId(), request.kbId(), request.fileExtension());
        try {
            String payload = objectMapper.writeValueAsString(request);
            kafkaTemplate.send(topic, request.documentId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            if (result != null && result.getRecordMetadata() != null) {
                                log.info("[Upload Pipeline][DISPATCH] Kafka 消息发送成功: documentId={}, topic={}, partition={}, offset={}",
                                        request.documentId(),
                                        result.getRecordMetadata().topic(),
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                            } else {
                                log.info("[Upload Pipeline][DISPATCH] Kafka 消息发送成功: topic={}, documentId={}", topic, request.documentId());
                            }
                        } else {
                            log.error("[Upload Pipeline][DISPATCH] Kafka 消息发送失败: topic={}, documentId={}",
                                    topic, request.documentId(), ex);
                        }
                    });
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize document parse payload", e);
        }
    }

    /**
     * 发送文档删除请求，通知消费端清除对应的向量数据。
     *
     * @param request 包含文档与租户标识的删除请求
     */
    public void sendDocumentDeletedRequest(DocumentDeleteRequestDTO request) {
        String topic = kafkaProperties.getTopics().getDeleteRequest();
        log.info("[DocumentMessageProducer] Sending Kafka message. topic={}, documentId={}, tenantId={}",
                topic, request.documentId(), request.tenantId());
        try {
            String payload = objectMapper.writeValueAsString(request);
            kafkaTemplate.send(topic, request.documentId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("[DocumentMessageProducer] Successfully sent Kafka message. topic={}, documentId={}",
                                    topic, request.documentId());
                        } else {
                            log.error("[DocumentMessageProducer] Failed to send Kafka message. topic={}, documentId={}",
                                    topic, request.documentId(), ex);
                        }
                    });
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize document delete payload", e);
        }
    }
}
