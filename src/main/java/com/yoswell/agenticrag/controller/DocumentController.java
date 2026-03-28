package com.yoswell.agenticrag.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.service.mq.DocumentMessageProducer;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentMessageProducer documentMessageProducer;

    public DocumentController(DocumentMessageProducer documentMessageProducer) {
        this.documentMessageProducer = documentMessageProducer;
    }

    /**
     * 接收前端文档上传。动作：
     * 1. 存入 MinIO
     * 2. 调用 DocumentMessageProducer 将请求抛入 Kafka `doc-parse-request`
     * 3. 返回 documentId 供前端追踪 MinerU 消费与提取进度
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public Mono<Map<String, String>> uploadDocument(@RequestPart("file") FilePart file) {
        String documentId = "doc-" + UUID.randomUUID().toString();
        // 伪代码: String minioUrl = minioService.upload(file);
        String mockMinioUrl = "minio://agenticrag/" + file.filename();
        
        // 触发通过Kafka与Python端MinerU进行管道交互
        documentMessageProducer.sendDocParseRequest(documentId, mockMinioUrl, "pdf");

        return Mono.just(Map.of(
            "documentId", documentId, 
            "status", "UPLOADED_PENDING_PARSING"
        ));
    }

    /**
     * 获取指定文档清洗与向量化状态
     */
    @GetMapping("/{documentId}/status")
    public Mono<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        // 去 MySQL metadata 里取 documentId 的 status (PARSING, CHUNKING, VECTORIZING, READY)
        return Mono.just(Map.of(
            "documentId", documentId, 
            "status", "VECTORIZING",
            "progress", "75%"
        ));
    }
}
