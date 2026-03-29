package com.yoswell.agenticrag.retrieval.document.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.service.DocumentService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识库文档上传与状态查询控制器
 *
 * <p>该控制器仅负责接收 HTTP 请求、获取租户上下文信息，并将工作交由下层服务处理。
 * 耗时的文档解析、文本分割、向量化存储逻辑不在此处阻塞执行，而是采用异步任务链在后台处理。</p>
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
     * 接收文档上传请求并启动异步处理链路
     *
     * <p>接口返回成功仅表示“上传已受理并成功建单”，并不意味着文档已经完成解析或向量化。
     * 客户端需根据返回的 documentId 轮询查询处理状态。</p>
     *
     * @param file WebFlux 支持的响应式文件片段数据 (FilePart)
     * @return 包含 documentId（文档唯一标识）、当前处理状态及 MinIO 对象存储地址的响应
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public Mono<Map<String, String>> uploadDocument(@RequestPart("file") FilePart file) {
        return SecurityUtils.getCurrentTenantId()
                .flatMap(tenantId -> documentService.handleReactiveUpload(file, tenantId)
                        .map(metadata -> Map.of(
                                "documentId", metadata.getDocumentId(),
                                "status", metadata.getStatus(),
                                "minioUrl", metadata.getMinioUrl()
                        )));
    }

    /**
     * 根据文档 ID 查询当前处理状态摘要
     *
     * @param documentId 文档业务 ID
     * @return 包含文档处理进度/状态的响应流
     */
    @GetMapping("/{documentId}/status")
    public Mono<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        // 注意：这里的 service 方法需要查库，由于可能存在同步阻塞方法调用，因此调度到 boundedElastic 线程池中执行
        return SecurityUtils.getCurrentTenantId()
                .flatMap(tenantId -> Mono.fromCallable(() -> documentService.getDocumentStatusDetails(documentId, tenantId))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 获取当前租户下归属的所有文档列表
     *
     * @return 包含文档元数据的 DTO 列表响应单流
     */
    @GetMapping
    public Mono<List<DocumentDTO>> getUserDocuments() {
        return SecurityUtils.getCurrentTenantId()
                .flatMap(tenantId -> Mono.fromCallable(() -> documentService.getUserDocuments(tenantId))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 删除指定文档
     * 
     * <p>同步删除文档状态信息记录、ElasticSearch 知识库向量索引块，以及底层对象存储服务(MinIO)上的物理实体文件。</p>
     *
     * @param documentId 文档业务 ID
     * @return 响应式完成信号 (Mono<Void>)
     */
    @DeleteMapping("/{documentId}")
    public Mono<Void> deleteDocument(@PathVariable String documentId) {
        return SecurityUtils.getCurrentTenantId()
                .flatMap(tenantId -> Mono.fromRunnable(() -> documentService.deleteDocumentById(documentId, tenantId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .then());
    }
}
