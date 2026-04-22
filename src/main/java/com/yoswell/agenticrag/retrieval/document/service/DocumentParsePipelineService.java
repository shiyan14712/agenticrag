package com.yoswell.agenticrag.retrieval.document.service;

import com.yoswell.agenticrag.retrieval.document.dto.request.DocumentParseRequestDTO;

/**
 * 文档解析编排服务接口
 */
public interface DocumentParsePipelineService {

    ParseDispatchResult parseAndDispatch(DocumentParseRequestDTO request);

    record ParseDispatchResult(String markdownFileUrl,
                               String mineruTaskId,
                               String vectorizeTaskId,
                               String vectorizeMessageId) {
    }
}
