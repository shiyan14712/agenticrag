package com.yoswell.agenticrag.retrieval.document.controller;

import java.util.List;
import java.util.Map;

import com.yoswell.agenticrag.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yoswell.agenticrag.common.result.ApiResponse;
import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.dto.response.DocumentUploadResponseDTO;
import com.yoswell.agenticrag.retrieval.document.dto.response.EntityRegistryEntryDTO;
import com.yoswell.agenticrag.retrieval.document.model.ChunkingStrategy;
import com.yoswell.agenticrag.retrieval.document.service.DocumentService;
import com.yoswell.agenticrag.web.security.util.SecurityUtils;

/**
 * 文档管理控制器
 *
 * <p>提供文档上传、状态查询、列表查询与删除能力</p>
 * <p>接口内部使用当前租户上下文执行隔离访问，确保文档读写操作仅作用于当前租户数据域</p>
 * <p>除流式接口外，统一返回 ApiResponse 业务响应结构</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档并创建处理任务
     *
     * <p>上传成功仅表示任务已创建，后续解析/向量化由异步链路完成</p>
     *
     * @param file 上传文件
     * @return 包含文档 ID、处理状态和存储地址的统一响应
     * @throws Exception 文件读取或上传链路异常
     */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<DocumentUploadResponseDTO> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkingStrategy", defaultValue = "STANDARD") ChunkingStrategy chunkingStrategy) throws BusinessException {
        String tenantId = SecurityUtils.getCurrentTenantId();
        var metadata = documentService.handleUpload(file, tenantId, chunkingStrategy);
        
        DocumentUploadResponseDTO response = new DocumentUploadResponseDTO(
                metadata.getDocumentId(),
                metadata.getStatus().value(),
                metadata.getMinioUrl()
        );
        
        return ApiResponse.success(response);
    }

    /**
     * 查询文档处理状态
     *
     * @param documentId 文档 ID
     * @return 包含状态详情的统一响应
     */
    @GetMapping("/{documentId}/status")
    public ApiResponse<Map<String, String>> getDocumentStatus(@PathVariable String documentId) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return ApiResponse.success(documentService.getDocumentStatusDetails(documentId, tenantId));
    }

    /**
     * 查询当前租户文档列表
     *
     * @return 包含文档 DTO 列表的统一响应
     */
    @GetMapping
    public ApiResponse<List<DocumentDTO>> getUserDocuments() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return ApiResponse.success(documentService.getUserDocuments(tenantId));
    }

    /**
     * 删除指定文档
     *
     * <p>删除包含元数据及其关联资源清理逻辑，具体由服务层保证事务边界</p>
     *
     * @param documentId 文档 ID
     * @return 统一响应（data 为 null）
     */
    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        documentService.deleteDocumentById(documentId, tenantId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{documentId}/entity-registry")
    public ApiResponse<List<EntityRegistryEntryDTO>> getEntityRegistry(@PathVariable String documentId) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return ApiResponse.success(documentService.getDocumentEntities(documentId, tenantId));
    }
}
