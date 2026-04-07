package com.yoswell.agenticrag.retrieval.document.reliability.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.MessageOutboxDO;
import com.yoswell.agenticrag.retrieval.document.reliability.mapper.MessageOutboxMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.model.MessageOutboxStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentOutboxService {

    private final MessageOutboxMapper messageOutboxMapper;
    private final ObjectMapper objectMapper;

    public DocumentOutboxService(MessageOutboxMapper messageOutboxMapper, ObjectMapper objectMapper) {
        this.messageOutboxMapper = messageOutboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MessageOutboxDO enqueue(String aggregateType,
                                   String aggregateId,
                                   String taskId,
                                   String eventType,
                                   String topic,
                                   String messageKey,
                                   Object payload) {
        MessageOutboxDO outbox = new MessageOutboxDO();
        outbox.setOutboxId("outbox-" + UUID.randomUUID());
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setTaskId(taskId);
        outbox.setEventType(eventType);
        outbox.setTopic(topic);
        outbox.setMessageKey(messageKey);
        outbox.setPayload(serialize(payload));
        outbox.setStatus(MessageOutboxStatus.PENDING.value());
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(LocalDateTime.now());
        messageOutboxMapper.insert(outbox);
        return outbox;
    }

    public List<MessageOutboxDO> findDueMessages(int limit) {
        return messageOutboxMapper.selectList(new LambdaQueryWrapper<MessageOutboxDO>()
                .in(MessageOutboxDO::getStatus, MessageOutboxStatus.PENDING.value(), MessageOutboxStatus.FAILED.value())
                .le(MessageOutboxDO::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(MessageOutboxDO::getId)
                .last("LIMIT " + limit));
    }

    @Transactional
    public boolean tryMarkDispatching(Long id) {
        if (id == null) {
            return false;
        }
        return messageOutboxMapper.update(null, new LambdaUpdateWrapper<MessageOutboxDO>()
                .eq(MessageOutboxDO::getId, id)
                .in(MessageOutboxDO::getStatus, MessageOutboxStatus.PENDING.value(), MessageOutboxStatus.FAILED.value())
                .set(MessageOutboxDO::getStatus, MessageOutboxStatus.DISPATCHING.value())
                .set(MessageOutboxDO::getLastError, null)) > 0;
    }

    @Transactional
    public void markSent(String outboxId) {
        messageOutboxMapper.update(null, new LambdaUpdateWrapper<MessageOutboxDO>()
                .eq(MessageOutboxDO::getOutboxId, outboxId)
                .set(MessageOutboxDO::getStatus, MessageOutboxStatus.SENT.value())
                .set(MessageOutboxDO::getSentAt, LocalDateTime.now())
                .set(MessageOutboxDO::getLastError, null));
    }

    @Transactional
    public void markFailed(String outboxId, String errorSummary, LocalDateTime nextRetryAt) {
        messageOutboxMapper.update(null, new LambdaUpdateWrapper<MessageOutboxDO>()
                .eq(MessageOutboxDO::getOutboxId, outboxId)
                .set(MessageOutboxDO::getStatus, MessageOutboxStatus.FAILED.value())
                .setSql("retry_count = retry_count + 1")
                .set(MessageOutboxDO::getNextRetryAt, nextRetryAt)
                .set(MessageOutboxDO::getLastError, truncate(errorSummary)));
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Failed to serialize outbox payload", exception);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 997) + "...";
    }
}
