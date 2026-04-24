package com.yoswell.agenticrag.retrieval.document.reliability.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoswell.agenticrag.common.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.MqConsumeLogDO;
import com.yoswell.agenticrag.retrieval.document.mapper.MqConsumeLogMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.model.MqConsumeStatus;

@Service
public class MqConsumeLogService {

    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final DocumentKafkaProperties kafkaProperties;

    public MqConsumeLogService(MqConsumeLogMapper mqConsumeLogMapper, DocumentKafkaProperties kafkaProperties) {
        this.mqConsumeLogMapper = mqConsumeLogMapper;
        this.kafkaProperties = kafkaProperties;
    }

    @Transactional
    public ConsumeClaimDecision claim(String consumerGroup,
                                      String topic,
                                      String messageIdentity,
                                      String messageKey,
                                      String payload,
                                      String taskId,
                                      String documentId) {
        MqConsumeLogDO existing = findOne(consumerGroup, topic, messageIdentity);
        if (existing == null) {
            try {
                mqConsumeLogMapper.insert(buildProcessingLog(
                        consumerGroup, topic, messageIdentity, messageKey, payload, taskId, documentId));
                return ConsumeClaimDecision.process();
            } catch (DuplicateKeyException duplicateKeyException) {
                existing = findOne(consumerGroup, topic, messageIdentity);
            }
        }

        if (existing == null) {
            return ConsumeClaimDecision.retryLater("consume log claim race");
        }
        if (MqConsumeStatus.SUCCEEDED.value().equals(existing.getStatus())
                || MqConsumeStatus.SKIPPED.value().equals(existing.getStatus())) {
            return ConsumeClaimDecision.alreadyCompleted();
        }

        LocalDateTime now = LocalDateTime.now();
        boolean lockExpired = existing.getLockedUntil() == null || !existing.getLockedUntil().isAfter(now);
        if (!lockExpired && MqConsumeStatus.PROCESSING.value().equals(existing.getStatus())) {
            return ConsumeClaimDecision.retryLater("message is still being processed");
        }

        boolean updated = mqConsumeLogMapper.update(null, new LambdaUpdateWrapper<MqConsumeLogDO>()
                .eq(MqConsumeLogDO::getId, existing.getId())
                .in(MqConsumeLogDO::getStatus, MqConsumeStatus.PROCESSING.value(), MqConsumeStatus.FAILED.value())
                .set(MqConsumeLogDO::getStatus, MqConsumeStatus.PROCESSING.value())
                .set(MqConsumeLogDO::getMessageKey, messageKey)
                .set(MqConsumeLogDO::getPayloadHash, payloadHash(payload))
                .set(MqConsumeLogDO::getTaskId, taskId)
                .set(MqConsumeLogDO::getDocumentId, documentId)
                .set(MqConsumeLogDO::getLockedUntil,
                        now.plus(Duration.ofMillis(kafkaProperties.getConsume().getProcessingTimeoutMs())))
                .set(MqConsumeLogDO::getStartedAt, now)
                .set(MqConsumeLogDO::getCompletedAt, null)
                .set(MqConsumeLogDO::getLastError, null)
                .setSql("consume_count = consume_count + 1")) > 0;
        return updated ? ConsumeClaimDecision.process() : ConsumeClaimDecision.retryLater("consume log update race");
    }

    @Transactional
    public void markSucceeded(String consumerGroup, String topic, String messageIdentity) {
        updateFinalStatus(consumerGroup, topic, messageIdentity, MqConsumeStatus.SUCCEEDED, null);
    }

    @Transactional
    public void markSkipped(String consumerGroup, String topic, String messageIdentity, String reason) {
        updateFinalStatus(consumerGroup, topic, messageIdentity, MqConsumeStatus.SKIPPED, reason);
    }

    @Transactional
    public void markFailed(String consumerGroup, String topic, String messageIdentity, String errorSummary) {
        updateFinalStatus(consumerGroup, topic, messageIdentity, MqConsumeStatus.FAILED, errorSummary);
    }

    public String resolveMessageIdentity(String explicitMessageId, String messageKey, String payload) {
        if (StringUtils.hasText(explicitMessageId)) {
            return explicitMessageId;
        }
        String safeKey = StringUtils.hasText(messageKey) ? messageKey : "anonymous";
        return safeKey + ":" + payloadHash(payload).substring(0, 16);
    }

    private MqConsumeLogDO findOne(String consumerGroup, String topic, String messageIdentity) {
        return mqConsumeLogMapper.selectOne(new LambdaQueryWrapper<MqConsumeLogDO>()
                .eq(MqConsumeLogDO::getConsumerGroup, consumerGroup)
                .eq(MqConsumeLogDO::getTopic, topic)
                .eq(MqConsumeLogDO::getMessageIdentity, messageIdentity));
    }

    private MqConsumeLogDO buildProcessingLog(String consumerGroup,
                                              String topic,
                                              String messageIdentity,
                                              String messageKey,
                                              String payload,
                                              String taskId,
                                              String documentId) {
        LocalDateTime now = LocalDateTime.now();
        MqConsumeLogDO consumeLog = new MqConsumeLogDO();
        consumeLog.setConsumerGroup(consumerGroup);
        consumeLog.setTopic(topic);
        consumeLog.setMessageIdentity(messageIdentity);
        consumeLog.setMessageKey(messageKey);
        consumeLog.setPayloadHash(payloadHash(payload));
        consumeLog.setTaskId(taskId);
        consumeLog.setDocumentId(documentId);
        consumeLog.setStatus(MqConsumeStatus.PROCESSING.value());
        consumeLog.setConsumeCount(1);
        consumeLog.setLockedUntil(now.plus(Duration.ofMillis(kafkaProperties.getConsume().getProcessingTimeoutMs())));
        consumeLog.setStartedAt(now);
        return consumeLog;
    }

    private void updateFinalStatus(String consumerGroup,
                                   String topic,
                                   String messageIdentity,
                                   MqConsumeStatus status,
                                   String errorSummary) {
        mqConsumeLogMapper.update(null, new LambdaUpdateWrapper<MqConsumeLogDO>()
                .eq(MqConsumeLogDO::getConsumerGroup, consumerGroup)
                .eq(MqConsumeLogDO::getTopic, topic)
                .eq(MqConsumeLogDO::getMessageIdentity, messageIdentity)
                .set(MqConsumeLogDO::getStatus, status.value())
                .set(MqConsumeLogDO::getLockedUntil, null)
                .set(MqConsumeLogDO::getCompletedAt, LocalDateTime.now())
                .set(MqConsumeLogDO::getLastError, truncate(errorSummary)));
    }

    private String payloadHash(String payload) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 997) + "...";
    }

    public record ConsumeClaimDecision(Action action, String reason) {

        public static ConsumeClaimDecision process() {
            return new ConsumeClaimDecision(Action.PROCESS, null);
        }

        public static ConsumeClaimDecision alreadyCompleted() {
            return new ConsumeClaimDecision(Action.ALREADY_COMPLETED, null);
        }

        public static ConsumeClaimDecision retryLater(String reason) {
            return new ConsumeClaimDecision(Action.RETRY_LATER, reason);
        }

        public boolean shouldProcess() {
            return action == Action.PROCESS;
        }

        public boolean isAlreadyCompleted() {
            return action == Action.ALREADY_COMPLETED;
        }
    }

    public enum Action {
        PROCESS,
        ALREADY_COMPLETED,
        RETRY_LATER
    }
}
