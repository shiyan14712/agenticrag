package com.yoswell.agenticrag.retrieval.document.reliability.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.retrieval.document.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.mq.producer.DocumentMessageProducer;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.MessageOutboxDO;

@Component
public class DocumentOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DocumentOutboxDispatcher.class);

    private final DocumentOutboxService documentOutboxService;
    private final DocumentAsyncTaskService documentAsyncTaskService;
    private final DocumentMessageProducer documentMessageProducer;
    private final DocumentKafkaProperties kafkaProperties;

    public DocumentOutboxDispatcher(DocumentOutboxService documentOutboxService,
                                    DocumentAsyncTaskService documentAsyncTaskService,
                                    DocumentMessageProducer documentMessageProducer,
                                    DocumentKafkaProperties kafkaProperties) {
        this.documentOutboxService = documentOutboxService;
        this.documentAsyncTaskService = documentAsyncTaskService;
        this.documentMessageProducer = documentMessageProducer;
        this.kafkaProperties = kafkaProperties;
    }

    @Scheduled(fixedDelayString = "#{@documentKafkaProperties.outbox.pollIntervalMs}")
    public void dispatchPendingMessages() {
        if (!kafkaProperties.getOutbox().isEnabled()) {
            return;
        }
        List<MessageOutboxDO> dueMessages =
                documentOutboxService.findDueMessages(kafkaProperties.getOutbox().getBatchSize());
        for (MessageOutboxDO outbox : dueMessages) {
            dispatchOne(outbox);
        }
    }

    private void dispatchOne(MessageOutboxDO outbox) {
        if (!documentOutboxService.tryMarkDispatching(outbox.getId())) {
            return;
        }
        try {
            documentMessageProducer.send(outbox.getTopic(), outbox.getMessageKey(), outbox.getPayload())
                    .get(kafkaProperties.getOutbox().getDispatchTimeoutMs(), TimeUnit.MILLISECONDS);
            documentOutboxService.markSent(outbox.getOutboxId());
            try {
                documentAsyncTaskService.markDispatched(outbox.getTaskId(), outbox.getOutboxId());
            } catch (Exception taskUpdateException) {
                log.warn("[Outbox] Task status update failed after message was sent. outboxId={}, taskId={}",
                        outbox.getOutboxId(), outbox.getTaskId(), taskUpdateException);
            }
            log.info("[Outbox] Message dispatched successfully. outboxId={}, topic={}, key={}",
                    outbox.getOutboxId(), outbox.getTopic(), outbox.getMessageKey());
        } catch (Exception exception) {
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(Duration.ofMillis(nextDelayMillis(outbox)));
            documentOutboxService.markFailed(outbox.getOutboxId(), exception.getMessage(), nextRetryAt);
            log.error("[Outbox] Message dispatch failed. outboxId={}, topic={}, key={}, nextRetryAt={}",
                    outbox.getOutboxId(), outbox.getTopic(), outbox.getMessageKey(), nextRetryAt, exception);
        }
    }

    private long nextDelayMillis(MessageOutboxDO outbox) {
        int retryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
        long interval = kafkaProperties.getRetry().getIntervalMs();
        for (int i = 0; i < retryCount; i++) {
            interval = Math.min(
                    kafkaProperties.getRetry().getMaxIntervalMs(),
                    Math.round(interval * kafkaProperties.getRetry().getMultiplier()));
        }
        return interval;
    }
}
