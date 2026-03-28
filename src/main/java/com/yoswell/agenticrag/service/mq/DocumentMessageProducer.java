package com.yoswell.agenticrag.service.mq;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DocumentMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageProducer.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DocumentMessageProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 发送文档解析请求到 Kafka (MinerU Python Worker 将消费)
     */
    public void sendDocParseRequest(String documentId, String minioUrl, String extension) {
        log.info("Sending doc-parse-request for documentId: {}, url: {}", documentId, minioUrl);
        Map<String, Object> message = Map.of(
                "documentId", documentId,
                "fileUrl", minioUrl,
                "extension", extension,
                "timestamp", System.currentTimeMillis()
        );
        
        kafkaTemplate.send("doc-parse-request", documentId, message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Successfully sent doc-parse-request message for document: {}", documentId);
                    } else {
                        log.error("Failed to send doc-parse-request message for document: {}", documentId, ex);
                        // 可以选择将其发入死信队列或标记状态为 FAILED
                    }
                });
    }
}
