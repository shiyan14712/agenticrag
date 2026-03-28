package com.yoswell.agenticrag.retrieval.document.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.dto.DocumentParseRequest;

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
    public void sendDocParseRequest(DocumentParseRequest request) {
        log.info("Sending doc-parse-request for documentId: {}, url: {}", request.documentId(), request.fileUrl());
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
}
