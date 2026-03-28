package com.yoswell.agenticrag.retrieval.document.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class DocumentMessageListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageListener.class);

    @KafkaListener(topics = "doc-vectorize-request", groupId = "agenticrag-group")
    public void listenVectorizeRequest(String message) {
        log.info("Received doc-vectorize-request from Kafka: {}", message);
        // TODO: doc-vectorize-request is currently a fake implementation that only prints logs. Needs real vectorization.
    }

    @KafkaListener(topics = "doc-dlq", groupId = "agenticrag-group")
    public void listenDeadLetterQueue(String message) {
        log.error("Received failed document processing from doc-dlq: {}", message);
    }
}
