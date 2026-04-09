package com.yoswell.agenticrag.retrieval.document.mq.consumer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentDeleteRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;
import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentVectorizeRequestDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;
import com.yoswell.agenticrag.retrieval.document.mapper.DocumentMetadataMapper;
import com.yoswell.agenticrag.retrieval.document.config.DocumentKafkaProperties;
import com.yoswell.agenticrag.retrieval.document.model.DocumentVectorizationExecutionResult;
import com.yoswell.agenticrag.retrieval.document.reliability.entity.DocumentAsyncTaskDO;
import com.yoswell.agenticrag.retrieval.document.reliability.model.DocumentAsyncTaskType;
import com.yoswell.agenticrag.retrieval.document.reliability.service.DocumentAsyncTaskService;
import com.yoswell.agenticrag.retrieval.document.reliability.service.MqConsumeLogService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentParsePipelineService;
import com.yoswell.agenticrag.retrieval.document.service.DocumentVectorizationService;
import com.yoswell.agenticrag.retrieval.document.service.KnowledgeChunkIndexService;

import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentMessageListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMessageListener.class);

    private final ObjectMapper objectMapper;
    private final DocumentParsePipelineService documentParsePipelineService;
    private final DocumentVectorizationService documentVectorizationService;
    private final KnowledgeChunkIndexService knowledgeChunkIndexService;
    private final DocumentKafkaProperties kafkaProperties;
    private final DocumentAsyncTaskService documentAsyncTaskService;
    private final MqConsumeLogService mqConsumeLogService;
    private final DocumentMetadataMapper documentMetadataMapper;
    private final String consumerGroupId;

    public DocumentMessageListener(ObjectMapper objectMapper,
                                   DocumentParsePipelineService documentParsePipelineService,
                                   DocumentVectorizationService documentVectorizationService,
                                   KnowledgeChunkIndexService knowledgeChunkIndexService,
                                   DocumentKafkaProperties kafkaProperties,
                                   DocumentAsyncTaskService documentAsyncTaskService,
                                   MqConsumeLogService mqConsumeLogService,
                                   DocumentMetadataMapper documentMetadataMapper,
                                   KafkaProperties springKafkaProperties) {
        this.objectMapper = objectMapper;
        this.documentParsePipelineService = documentParsePipelineService;
        this.documentVectorizationService = documentVectorizationService;
        this.knowledgeChunkIndexService = knowledgeChunkIndexService;
        this.kafkaProperties = kafkaProperties;
        this.documentAsyncTaskService = documentAsyncTaskService;
        this.mqConsumeLogService = mqConsumeLogService;
        this.documentMetadataMapper = documentMetadataMapper;
        this.consumerGroupId = springKafkaProperties.getConsumer().getGroupId();
    }

    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.parseRequest}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    public void listenParseRequest(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.info("[Offline RAG][PARSE_CONSUMER] Received Kafka message. topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        try {
            DocumentParseRequestDTO request = objectMapper.readValue(message, DocumentParseRequestDTO.class);
            requireDocumentId(request.documentId());

            String tenantId = resolveTenantId(request.documentId(), request.tenantId());
            DocumentAsyncTaskDO task = StringUtils.hasText(request.taskId())
                    ? documentAsyncTaskService.findByTaskId(request.taskId())
                    : null;
            if (task == null) {
                task = documentAsyncTaskService.getOrCreateTask(
                        request.documentId(),
                        tenantId,
                        DocumentAsyncTaskType.DOCUMENT_PARSE,
                        record.topic(),
                        record.key());
            }

            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            assertCanProcess(record.topic(), messageIdentity, record.key(), message, task.getTaskId(), request.documentId());

            documentAsyncTaskService.markRunning(task.getTaskId(), messageIdentity);
            DocumentParsePipelineService.ParseDispatchResult parseResult =
                    documentParsePipelineService.parseAndDispatch(request);

            documentAsyncTaskService.markSucceeded(task.getTaskId(), messageIdentity);
            mqConsumeLogService.markSucceeded(consumerGroupId, record.topic(), messageIdentity);
            log.info("[Offline RAG][PARSE_CONSUMER] Parse completed and vectorize message dispatched. documentId={}, parseTaskId={}, vectorizeTaskId={}, vectorizeMessageId={}, mineruTaskId={}",
                    request.documentId(),
                    task.getTaskId(),
                    parseResult.vectorizeTaskId(),
                    parseResult.vectorizeMessageId(),
                    parseResult.mineruTaskId());
            acknowledgment.acknowledge();
        } catch (AlreadyCompletedException alreadyCompletedException) {
            acknowledgment.acknowledge();
        } catch (RetryLaterException retryLaterException) {
            throw retryLaterException;
        } catch (Exception exception) {
            handleParseFailure(record, message, exception);
            throw new RuntimeException("document parse processing failed", exception);
        }
    }

    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.vectorizeRequest}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    public void listenVectorizeRequest(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.info("[Offline RAG][VECTORIZE_CONSUMER] Received Kafka message. topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        try {
            DocumentVectorizeRequestDTO request = objectMapper.readValue(message, DocumentVectorizeRequestDTO.class);
            DocumentAsyncTaskDO task = documentAsyncTaskService.getOrCreateTask(
                    request.documentId(),
                    resolveTenantId(request.documentId(), request.tenantId()),
                    DocumentAsyncTaskType.DOCUMENT_VECTORIZATION,
                    record.topic(),
                    record.key());
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            assertCanProcess(record.topic(), messageIdentity, record.key(), message, task.getTaskId(), request.documentId());

            documentAsyncTaskService.markSucceededByDocumentAndType(
                    request.documentId(),
                    DocumentAsyncTaskType.DOCUMENT_PARSE,
                    messageIdentity);
            documentAsyncTaskService.markRunning(task.getTaskId(), messageIdentity);

            DocumentVectorizationExecutionResult result = documentVectorizationService.vectorize(request);
            if (result.skipped()) {
                documentAsyncTaskService.markSkipped(task.getTaskId(), messageIdentity, result.detail());
                mqConsumeLogService.markSkipped(consumerGroupId, record.topic(), messageIdentity, result.detail());
            } else {
                documentAsyncTaskService.markSucceeded(task.getTaskId(), messageIdentity);
                mqConsumeLogService.markSucceeded(consumerGroupId, record.topic(), messageIdentity);
            }
            acknowledgment.acknowledge();
        } catch (AlreadyCompletedException alreadyCompletedException) {
            acknowledgment.acknowledge();
        } catch (RetryLaterException retryLaterException) {
            throw retryLaterException;
        } catch (Exception exception) {
            handleVectorizeFailure(record, message, exception);
            throw new RuntimeException("document vectorize processing failed", exception);
        }
    }

    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.deleteRequest}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    public void listenDocumentDeleteRequest(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        log.info("[Offline RAG][DELETE_CONSUMER] Received Kafka message. topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        try {
            DocumentDeleteRequestDTO request = objectMapper.readValue(message, DocumentDeleteRequestDTO.class);
            DocumentAsyncTaskDO task = StringUtils.hasText(request.taskId())
                    ? documentAsyncTaskService.findByTaskId(request.taskId())
                    : documentAsyncTaskService.getOrCreateTask(
                            request.documentId(),
                            request.tenantId(),
                            DocumentAsyncTaskType.DOCUMENT_DELETE,
                            record.topic(),
                            record.key());
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            assertCanProcess(record.topic(), messageIdentity, record.key(), message,
                    task == null ? null : task.getTaskId(), request.documentId());

            if (task != null) {
                documentAsyncTaskService.markRunning(task.getTaskId(), messageIdentity);
            }
            knowledgeChunkIndexService.deleteByDocumentId(request.documentId(), request.tenantId());
            if (task != null) {
                documentAsyncTaskService.markSucceeded(task.getTaskId(), messageIdentity);
            }
            mqConsumeLogService.markSucceeded(consumerGroupId, record.topic(), messageIdentity);
            acknowledgment.acknowledge();
        } catch (AlreadyCompletedException alreadyCompletedException) {
            acknowledgment.acknowledge();
        } catch (RetryLaterException retryLaterException) {
            throw retryLaterException;
        } catch (Exception exception) {
            handleDeleteFailure(record, message, exception);
            throw new RuntimeException("document delete processing failed", exception);
        }
    }

    @KafkaListener(
            topics = "#{@documentKafkaProperties.topics.deadLetter}",
            containerFactory = "documentKafkaListenerContainerFactory"
    )
    @SuppressWarnings("unchecked")
    public void listenDeadLetterQueue(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String message = record.value();
        String originalTopic = readHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        log.error("[Offline RAG][DLQ_CONSUMER] Received dead-letter message. topic={}, originalTopic={}, key={}",
                record.topic(), originalTopic, record.key());
        try {
            Map<String, Object> payload = objectMapper.readValue(message, Map.class);
            String explicitMessageId = payload.get("messageId") instanceof String value ? value : null;
            String documentId = payload.get("documentId") instanceof String value ? value : null;
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(explicitMessageId, record.key(), message);
            DocumentAsyncTaskType taskType = resolveTaskType(originalTopic);
            DocumentAsyncTaskDO task = taskType == null || !StringUtils.hasText(documentId)
                    ? null
                    : documentAsyncTaskService.findByDocumentAndType(documentId, taskType);

            assertCanProcess(record.topic(), messageIdentity, record.key(), message,
                    task == null ? null : task.getTaskId(), documentId);

            if (StringUtils.hasText(documentId) && kafkaProperties.getTopics().getVectorizeRequest().equals(originalTopic)) {
                documentVectorizationService.markFailed(documentId);
            }
            if (StringUtils.hasText(documentId) && kafkaProperties.getTopics().getParseRequest().equals(originalTopic)) {
                documentVectorizationService.markFailed(documentId);
            }
            if (task != null) {
                documentAsyncTaskService.markFailed(task.getTaskId(), messageIdentity,
                        "moved to dead letter from " + originalTopic);
            }
            mqConsumeLogService.markSucceeded(consumerGroupId, record.topic(), messageIdentity);
            acknowledgment.acknowledge();
        } catch (AlreadyCompletedException alreadyCompletedException) {
            acknowledgment.acknowledge();
        } catch (RetryLaterException retryLaterException) {
            throw retryLaterException;
        } catch (Exception exception) {
            log.warn("[Offline RAG][DLQ_CONSUMER] Failed to process dead-letter payload", exception);
            throw new RuntimeException("document dead-letter processing failed", exception);
        }
    }

    private void handleParseFailure(ConsumerRecord<String, String> record, String message, Exception exception) {
        try {
            DocumentParseRequestDTO request = objectMapper.readValue(message, DocumentParseRequestDTO.class);
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            DocumentAsyncTaskDO task = StringUtils.hasText(request.taskId())
                    ? documentAsyncTaskService.findByTaskId(request.taskId())
                    : documentAsyncTaskService.findByDocumentAndType(
                            request.documentId(),
                            DocumentAsyncTaskType.DOCUMENT_PARSE);
            if (task != null) {
                documentAsyncTaskService.markFailed(task.getTaskId(), messageIdentity, exception.getMessage());
            }
            if (StringUtils.hasText(request.documentId())) {
                documentVectorizationService.markFailed(request.documentId());
            }
            mqConsumeLogService.markFailed(consumerGroupId, record.topic(), messageIdentity, exception.getMessage());
        } catch (Exception nestedException) {
            log.warn("[Offline RAG][PARSE_CONSUMER] Failed to record consume failure", nestedException);
        }
    }

    private void handleVectorizeFailure(ConsumerRecord<String, String> record, String message, Exception exception) {
        try {
            DocumentVectorizeRequestDTO request = objectMapper.readValue(message, DocumentVectorizeRequestDTO.class);
            DocumentAsyncTaskDO task = documentAsyncTaskService.findByDocumentAndType(
                    request.documentId(),
                    DocumentAsyncTaskType.DOCUMENT_VECTORIZATION);
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            if (task != null) {
                documentAsyncTaskService.markFailed(task.getTaskId(), messageIdentity, exception.getMessage());
            }
            mqConsumeLogService.markFailed(consumerGroupId, record.topic(), messageIdentity, exception.getMessage());
        } catch (Exception nestedException) {
            log.warn("[Offline RAG][VECTORIZE_CONSUMER] Failed to record consume failure", nestedException);
        }
    }

    private void handleDeleteFailure(ConsumerRecord<String, String> record, String message, Exception exception) {
        try {
            DocumentDeleteRequestDTO request = objectMapper.readValue(message, DocumentDeleteRequestDTO.class);
            DocumentAsyncTaskDO task = StringUtils.hasText(request.taskId())
                    ? documentAsyncTaskService.findByTaskId(request.taskId())
                    : documentAsyncTaskService.findByDocumentAndType(
                            request.documentId(),
                            DocumentAsyncTaskType.DOCUMENT_DELETE);
            String messageIdentity = mqConsumeLogService.resolveMessageIdentity(
                    request.messageId(),
                    record.key(),
                    message);
            if (task != null) {
                documentAsyncTaskService.markFailed(task.getTaskId(), messageIdentity, exception.getMessage());
            }
            mqConsumeLogService.markFailed(consumerGroupId, record.topic(), messageIdentity, exception.getMessage());
        } catch (Exception nestedException) {
            log.warn("[Offline RAG][DELETE_CONSUMER] Failed to record consume failure", nestedException);
        }
    }

    private void assertCanProcess(String topic,
                                  String messageIdentity,
                                  String messageKey,
                                  String payload,
                                  String taskId,
                                  String documentId) {
        MqConsumeLogService.ConsumeClaimDecision decision = mqConsumeLogService.claim(
                consumerGroupId,
                topic,
                messageIdentity,
                messageKey,
                payload,
                taskId,
                documentId);
        if (decision.shouldProcess()) {
            return;
        }
        if (decision.isAlreadyCompleted()) {
            throw new AlreadyCompletedException();
        }
        throw new RetryLaterException(decision.reason());
    }

    private String readHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private DocumentAsyncTaskType resolveTaskType(String topic) {
        if (kafkaProperties.getTopics().getParseRequest().equals(topic)) {
            return DocumentAsyncTaskType.DOCUMENT_PARSE;
        }
        if (kafkaProperties.getTopics().getVectorizeRequest().equals(topic)) {
            return DocumentAsyncTaskType.DOCUMENT_VECTORIZATION;
        }
        if (kafkaProperties.getTopics().getDeleteRequest().equals(topic)) {
            return DocumentAsyncTaskType.DOCUMENT_DELETE;
        }
        return null;
    }

    private String resolveTenantId(String documentId, String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            return tenantId;
        }
        DocumentDO metadata = documentMetadataMapper.selectOne(new LambdaQueryWrapper<DocumentDO>()
                .eq(DocumentDO::getDocumentId, documentId)
                .select(DocumentDO::getTenantId));
        return metadata == null ? null : metadata.getTenantId();
    }

    private void requireDocumentId(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
    }

    private static final class RetryLaterException extends RuntimeException {
        private RetryLaterException(String message) {
            super(message);
        }
    }

    private static final class AlreadyCompletedException extends RuntimeException {
    }
}
