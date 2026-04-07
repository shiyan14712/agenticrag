package com.yoswell.agenticrag.retrieval.document.reliability.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.DocumentAsyncTaskDO;
import com.yoswell.agenticrag.retrieval.document.reliability.mapper.DocumentAsyncTaskMapper;
import com.yoswell.agenticrag.retrieval.document.reliability.model.DocumentAsyncTaskStatus;
import com.yoswell.agenticrag.retrieval.document.reliability.model.DocumentAsyncTaskType;

@Service
public class DocumentAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAsyncTaskService.class);

    private final DocumentAsyncTaskMapper documentAsyncTaskMapper;

    public DocumentAsyncTaskService(DocumentAsyncTaskMapper documentAsyncTaskMapper) {
        this.documentAsyncTaskMapper = documentAsyncTaskMapper;
    }

    @Transactional
    public DocumentAsyncTaskDO createTask(String documentId,
                                          String tenantId,
                                          DocumentAsyncTaskType taskType,
                                          String topic,
                                          String messageKey) {
        DocumentAsyncTaskDO task = new DocumentAsyncTaskDO();
        task.setTaskId("task-" + UUID.randomUUID());
        task.setDocumentId(documentId);
        task.setTenantId(tenantId);
        task.setTaskType(taskType.value());
        task.setStatus(DocumentAsyncTaskStatus.PENDING.value());
        task.setTopic(topic);
        task.setMessageKey(messageKey);
        task.setAttemptCount(0);
        try {
            documentAsyncTaskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException duplicateKeyException) {
            log.debug("Reusing existing async task for documentId={}, taskType={}", documentId, taskType.value());
            return findByDocumentAndType(documentId, taskType);
        }
    }

    @Transactional
    public DocumentAsyncTaskDO getOrCreateTask(String documentId,
                                               String tenantId,
                                               DocumentAsyncTaskType taskType,
                                               String topic,
                                               String messageKey) {
        DocumentAsyncTaskDO existing = findByDocumentAndType(documentId, taskType);
        if (existing != null) {
            return existing;
        }
        return createTask(documentId, tenantId, taskType, topic, messageKey);
    }

    public DocumentAsyncTaskDO findByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return documentAsyncTaskMapper.selectOne(new LambdaQueryWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId));
    }

    public DocumentAsyncTaskDO findByDocumentAndType(String documentId, DocumentAsyncTaskType taskType) {
        if (!StringUtils.hasText(documentId) || taskType == null) {
            return null;
        }
        return documentAsyncTaskMapper.selectOne(new LambdaQueryWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getDocumentId, documentId)
                .eq(DocumentAsyncTaskDO::getTaskType, taskType.value()));
    }

    @Transactional
    public void markDispatched(String taskId, String outboxId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId)
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.DISPATCHED.value())
                .set(DocumentAsyncTaskDO::getOutboxId, outboxId)
                .set(DocumentAsyncTaskDO::getLastError, null));
    }

    @Transactional
    public void markRunning(String taskId, String messageId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId)
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.RUNNING.value())
                .set(DocumentAsyncTaskDO::getLastMessageId, messageId)
                .set(DocumentAsyncTaskDO::getStartedAt, LocalDateTime.now())
                .set(DocumentAsyncTaskDO::getCompletedAt, null)
                .set(DocumentAsyncTaskDO::getLastError, null)
                .setSql("attempt_count = attempt_count + 1"));
    }

    @Transactional
    public void markSucceeded(String taskId, String messageId) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId)
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.SUCCEEDED.value())
                .set(DocumentAsyncTaskDO::getLastMessageId, messageId)
                .set(DocumentAsyncTaskDO::getCompletedAt, LocalDateTime.now())
                .set(DocumentAsyncTaskDO::getLastError, null));
    }

    @Transactional
    public void markFailed(String taskId, String messageId, String errorSummary) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId)
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.FAILED.value())
                .set(DocumentAsyncTaskDO::getLastMessageId, messageId)
                .set(DocumentAsyncTaskDO::getCompletedAt, LocalDateTime.now())
                .set(DocumentAsyncTaskDO::getLastError, truncate(errorSummary)));
    }

    @Transactional
    public void markSkipped(String taskId, String messageId, String reason) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getTaskId, taskId)
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.SKIPPED.value())
                .set(DocumentAsyncTaskDO::getLastMessageId, messageId)
                .set(DocumentAsyncTaskDO::getCompletedAt, LocalDateTime.now())
                .set(DocumentAsyncTaskDO::getLastError, truncate(reason)));
    }

    @Transactional
    public void markSucceededByDocumentAndType(String documentId, DocumentAsyncTaskType taskType, String messageId) {
        if (!StringUtils.hasText(documentId) || taskType == null) {
            return;
        }
        documentAsyncTaskMapper.update(null, new LambdaUpdateWrapper<DocumentAsyncTaskDO>()
                .eq(DocumentAsyncTaskDO::getDocumentId, documentId)
                .eq(DocumentAsyncTaskDO::getTaskType, taskType.value())
                .set(DocumentAsyncTaskDO::getStatus, DocumentAsyncTaskStatus.SUCCEEDED.value())
                .set(DocumentAsyncTaskDO::getLastMessageId, messageId)
                .set(DocumentAsyncTaskDO::getCompletedAt, LocalDateTime.now())
                .set(DocumentAsyncTaskDO::getLastError, null));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 997) + "...";
    }
}
