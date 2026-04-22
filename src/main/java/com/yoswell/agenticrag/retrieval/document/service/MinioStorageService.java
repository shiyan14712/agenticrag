package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;

/**
 * MinIO 存储服务接口
 */
public interface MinioStorageService {

    String uploadFile(String objectName, InputStream inputStream, long size, String contentType);

    byte[] readFile(String fileUrl);

    String readUtf8String(String fileUrl);

    void deleteFile(String fileUrl);
}
