package com.yoswell.agenticrag.retrieval.document.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.retrieval.document.service.DocumentService;
import com.yoswell.agenticrag.util.SecurityUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档上传与状态查询入口。
 *
 * <p>控制器本身只负责接收请求、获取租户上下文并把工作转交给服务层，
 * 不在这里执行耗时的存储、解析和向量化逻辑。</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 接收文档上传请求并启动异步处理链路。
     *
     * <p>接口返回时只表示“上传已受理并成功建单”，不表示文档已经解析或向量化完成。</p>
     *
     * @param file WebFlux 提供的上传文件片段
     * @return 包含 documentId、当前状态和对象存储地址的响应
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public Mono<Map<String, String>> uploadDocument(@RequestPart("file") FilePart file) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return documentService.handleReactiveUpload(file, tenantId)
                .map(metadata -> Map.of(
                        "documentId", metadata.getDocumentId(),
                        "status", metadata.getStatus(),
                        "minioUrl", metadata.getMinioUrl()
                ));
    }

    /**
     * 查询文档当前处理状态。
     *
     * @param documentId 文档业务 ID
     * @return 文档状态摘要
     */
    @GetMapping("/{documentId}/status")
    public Mono<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return Mono.fromCallable(() -> documentService.getDocumentStatusDetails(documentId, tenantId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
