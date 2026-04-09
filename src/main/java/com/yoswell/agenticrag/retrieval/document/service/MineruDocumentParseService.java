package com.yoswell.agenticrag.retrieval.document.service;

/**
 * MinerU 文档解析服务接口
 */
public interface MineruDocumentParseService {

    ParseResult parseToMarkdown(String fileName, byte[] fileBytes, String contentType);

    record ParseResult(String markdown, String mineruTaskId) {
    }
}
