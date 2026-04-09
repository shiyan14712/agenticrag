package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;

import com.yoswell.agenticrag.retrieval.document.dto.DocumentDTO;
import com.yoswell.agenticrag.retrieval.document.entity.DocumentDO;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    DocumentDO handleUpload(MultipartFile file, String tenantId);

    DocumentDO uploadAndDispatch(String fileName, InputStream inputStream, long fileSize, String contentType, String tenantId);

    DocumentDO getDocumentStatus(String documentId, String tenantId);

    Map<String, String> getDocumentStatusDetails(String documentId, String tenantId);

    List<DocumentDTO> getUserDocuments(String tenantId);

    void deleteDocumentById(String documentId, String tenantId);
}
