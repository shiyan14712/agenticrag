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

    public DocumentMessageProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送文档解析请求。
     *
     * @param request 解析阶段所需的上下文
     */
    public void sendDocParseRequest(DocumentParseRequestDTO request) {
        log.info("[DocumentMessageProducer] Sending doc-parse-request for documentId: {}, url: {}", request.documentId(), request.fileUrl());
        try {
            String payload = objectMapper.writeValueAsString(request);
            kafkaTemplate.send("doc-parse-request", request.documentId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Successfully sent doc-parse-request message for document: {}", request.documentId());
                        } else {
                            log.error("Failed to send doc-parse-request message for document: {}", request.documentId(), ex);
                        }
                    });
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize doc-parse-request payload", e);
        }
    }

    /**
     * 发送文档删除请求，通知消费端清除对应的向量数据。
     *
     * @param request 包含文档与租户标识的删除请求
     */
    public void sendDocumentDeletedRequest(DocumentDeleteRequestDTO request) {
        log.info("[DocumentMessageProducer] Sending doc-delete-request for documentId: {}, tenantId: {}", request.documentId(), request.tenantId());
        try {
            String payload = objectMapper.writeValueAsString(request);
            kafkaTemplate.send("doc-delete-request", request.documentId(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("[DocumentMessageProducer] Successfully sent doc-delete-request message for document: {}", request.documentId());
                        } else {
                            log.error("[DocumentMessageProducer] Failed to send doc-delete-request message for document: {}", request.documentId(), ex);
                        }
                    });
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize doc-delete-request payload", e);
        }
    }
}
