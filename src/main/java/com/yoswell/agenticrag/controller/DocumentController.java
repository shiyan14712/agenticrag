package com.yoswell.agenticrag.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.entity.DocumentMetadata;
import com.yoswell.agenticrag.security.TenantUser;
import com.yoswell.agenticrag.service.document.DocumentService;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 接收前端文档上传。动作：
     * 1. 将 FilePart 数据流写入临时文件后上传至 MinIO
     * 2. 在 MySQL 记录元数据（document_metadata）
     * 3. 触发 Kafka doc-parse-request 消息指引 MinerU 消费
     * 4. 返回 documentId 供前端追踪处理进度
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public Mono<Map<String, String>> uploadDocument(@RequestPart("file") FilePart file) {
        String tenantId = getCurrentTenantId();

        // FilePart.content() 返回 Flux<DataBuffer>，需要在 boundedElastic 线程上
        // 聚合为 InputStream 后交给阻塞的 MinioService
        return file.content()
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    // 释放 DataBuffer 以防止内存泄漏
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .collectList()
                .flatMap(byteArrayList -> Mono.fromCallable(() -> {
                    // 合并所有 DataBuffer 字节
                    int totalSize = byteArrayList.stream().mapToInt(b -> b.length).sum();
                    byte[] allBytes = new byte[totalSize];
                    int offset = 0;
                    for (byte[] chunk : byteArrayList) {
                        System.arraycopy(chunk, 0, allBytes, offset, chunk.length);
                        offset += chunk.length;
                    }

                    java.io.InputStream inputStream = new java.io.ByteArrayInputStream(allBytes);
                    String fileName = file.filename();
                    String contentType = file.headers().getContentType() != null
                            ? file.headers().getContentType().toString()
                            : "application/octet-stream";

                    DocumentMetadata metadata = documentService.uploadAndDispatch(
                            fileName, inputStream, totalSize, contentType, tenantId);

                    log.info("Document upload completed: documentId={}, fileName={}", metadata.getDocumentId(), fileName);

                    return Map.of(
                        "documentId", metadata.getDocumentId(),
                        "status", metadata.getStatus(),
                        "minioUrl", metadata.getMinioUrl()
                    );
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 获取指定文档清洗与向量化状态
     */
    @GetMapping("/{documentId}/status")
    public Mono<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        return Mono.fromCallable(() -> {
            DocumentMetadata metadata = documentService.getDocumentStatus(documentId);
            return Map.of(
                "documentId", metadata.getDocumentId(),
                "status", metadata.getStatus(),
                "fileName", metadata.getFileName() != null ? metadata.getFileName() : ""
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String getCurrentTenantId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof TenantUser tenantUser) {
            return tenantUser.getTenantId();
        }
        return "default";
    }
}
