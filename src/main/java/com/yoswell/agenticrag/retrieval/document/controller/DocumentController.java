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
 * 知识库文档资源管理控制器
 *
 * 专门处理外部文档注入到 RAG 系统的文件网关层。负责接收用户的多模态文件上传与请求映射，
 * 并驱动后台通过 MQ 等管道机制实施对这些文件的脱敏、存储、深度拆解甚至向量化工作。
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
     * 上传企业文档进入知识库解析管道
     *
     * 场景：用户需在界面长传如 PDF / Word 等不规则文档到系统知识库中。
     * 由于后端采取 Python 深度学习框架 (例如 MinerU) 剥离分析版面，耗时巨大。
     * 因此本接口仅响应上传接受状态，不做同步转换等待。
     *
     * @param file 由 Spring WebFlux 构建支持的异步数据流包装 (FilePart)
     * @return 包含 documentId 唯一追踪编号，及其后续轮询追踪状态的任务票据
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
     * 查询指定外部企业文档的解析预处理状态
     *
     * 场景：前端在呈现文档列表时，显示这篇文件是仍被压在消息队列 ("UPLOADED_PENDING_PARSING") 
     * 或是正在解析执行化中，又或最后已经被切割存下在 ES 里处于可命中状态。
     *
     * @param documentId 文件票据 ID (由上传接口初始化回传)
     * @return 文件的最新的内部元数据对象表示（脱水版）
     */
    @GetMapping("/{documentId}/status")
    public Mono<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return Mono.fromCallable(() -> documentService.getDocumentStatusDetails(documentId, tenantId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
