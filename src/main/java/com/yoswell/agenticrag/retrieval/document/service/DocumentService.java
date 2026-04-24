package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;

import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.dto.response.EntityRegistryEntryDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;

import com.yoswell.agenticrag.retrieval.document.model.ChunkingStrategy;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    DocumentDO handleUpload(MultipartFile file, String tenantId, ChunkingStrategy chunkingStrategy);

    DocumentDO uploadAndDispatch(String fileName, InputStream inputStream, long fileSize, String contentType, String tenantId, ChunkingStrategy chunkingStrategy);

    DocumentDO getDocumentStatus(String documentId, String tenantId);

    Map<String, String> getDocumentStatusDetails(String documentId, String tenantId);

    List<DocumentDTO> getUserDocuments(String tenantId);

    void deleteDocumentById(String documentId, String tenantId);

    List<EntityRegistryEntryDTO> getDocumentEntities(String documentId, String tenantId);
}
