package com.yoswell.agenticrag.retrieval.document.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
/**
 * 负责向文档处理相关 Kafka 主题投递消息。
 */
public class DocumentMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DocumentMessageProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 发送一条已经序列化完成的 Kafka 消息。
     *
     * @param topic Kafka topic
     * @param messageKey Kafka key
     * @param payload 序列化后的消息体
     * @return Kafka send future
     */
    public CompletableFuture<SendResult<String, String>> send(String topic, String messageKey, String payload) {
        log.info("[Kafka Producer] Sending message. topic={}, key={}, payloadSize={} chars",
                topic, messageKey, payload == null ? 0 : payload.length());
        return kafkaTemplate.send(topic, messageKey, payload)
                .whenComplete((result, ex) -> {
                    if (ex == null && result != null && result.getRecordMetadata() != null) {
                        log.info("[Kafka Producer] Message sent. topic={}, key={}, partition={}, offset={}",
                                result.getRecordMetadata().topic(),
                                messageKey,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else if (ex != null) {
                        log.error("[Kafka Producer] Failed to send message. topic={}, key={}", topic, messageKey, ex);
                    }
                });
    }
}
