package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.service.MinioStorageService;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

/**
 * 对 MinIO 阻塞 I/O 的轻量封装。
 *
 * <p>服务层通过它统一完成上传、读取和地址解析，避免上层到处散落
 * MinIO SDK 细节。</p>
 */
@Service
public class MinioStorageServiceImpl implements MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageServiceImpl.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:agenticrag}")
    private String defaultBucket;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioStorageServiceImpl(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 上传文件到 MinIO 并返回最终存储地址。
     *
     * @param objectName 对象名，通常由 documentId 和文件名组成
     * @param inputStream 文件流
     * @param size 文件大小，字节数
     * @param contentType MIME 类型
     * @return 文件在 MinIO 中的访问地址
     */
    @Override
    public String uploadFile(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            log.info("[MinIO][UPLOAD] 开始写入对象: bucket={}, objectName={}, size={} bytes", defaultBucket, objectName, size);
            ensureBucketExists(defaultBucket);

            PutObjectArgs putArgs = PutObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();

            minioClient.putObject(putArgs);

            String minioUrl = endpoint + "/" + defaultBucket + "/" + objectName;
            log.info("[MinIO][UPLOAD] 写入成功: bucket={}, objectName={}, minioUrl={}", defaultBucket, objectName, minioUrl);
            return minioUrl;
        } catch (Exception e) {
            log.error("[Minio Storage Service] Failed to upload file to MinIO: {}", objectName, e);
            throw new RuntimeException("MinIO upload failed for: " + objectName, e);
        }
    }

    /**
     * 按地址读取 MinIO 中文件的原始字节。
     *
     * @param fileUrl MinIO 地址
     * @return 文件字节数组
     */
    @Override
    public byte[] readFile(String fileUrl) {
        ParsedMinioLocation location = parseLocation(fileUrl);
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(location.bucket())
                        .object(location.objectName())
                        .build())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("[Minio Storage Service] Failed to read file from MinIO: {}", fileUrl, e);
            throw new RuntimeException("MinIO read failed for: " + fileUrl, e);
        }
    }

    /**
     * 以 UTF-8 方式读取文本文件内容。
     *
     * @param fileUrl MinIO 地址
     * @return 文本内容
     */
    @Override
    public String readUtf8String(String fileUrl) {
        byte[] rawBytes = readFile(fileUrl);
        String text = new String(rawBytes, StandardCharsets.UTF_8);
        log.info("[MinIO][READ_TEXT] 文本读取完成: fileUrl={}, bytes={}, chars={}", fileUrl, rawBytes.length, text.length());
        return text;
    }

    /**
     * 按地址删除 MinIO 中的文件。
     *
     * @param fileUrl MinIO 地址
     */
    @Override
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            return;
        }
        ParsedMinioLocation location = parseLocation(fileUrl);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(location.bucket())
                            .object(location.objectName())
                            .build()
            );
            log.info("File deleted from MinIO: {}", fileUrl);
        } catch (Exception e) {
            log.error("[Minio Storage Service] Failed to delete file from MinIO: {}", fileUrl, e);
            throw new RuntimeException("MinIO delete failed for: " + fileUrl, e);
        }
    }

    /**
     * 确保目标 bucket 存在，不存在时自动创建。
     *
     * @param bucket bucket 名称
     */
    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to ensure bucket exists: {}", bucket, e);
            throw new RuntimeException("MinIO bucket check failed", e);
        }
    }

    /**
     * 把外部 URL 解析为 MinIO SDK 所需的 bucket 和 objectName。
     *
     * <p>既支持 {@code minio://bucket/object} 形式，也支持基于 endpoint
     * 拼出的 HTTP 地址。</p>
     *
     * @param fileUrl 传入的文件地址
     * @return 解析后的 bucket 与 objectName
     */
    private ParsedMinioLocation parseLocation(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new IllegalArgumentException("File URL cannot be blank");
        }

        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String normalizedUrl = fileUrl.trim();

        if (normalizedUrl.startsWith("minio://")) {
            String path = normalizedUrl.substring("minio://".length());
            String[] segments = path.split("/", 2);
            if (segments.length < 2) {
                throw new IllegalArgumentException("Invalid MinIO URL: " + fileUrl);
            }
            return new ParsedMinioLocation(segments[0], segments[1]);
        }

        if (!normalizedUrl.startsWith(normalizedEndpoint + "/")) {
            throw new IllegalArgumentException("Unsupported MinIO URL: " + fileUrl);
        }

        String path = normalizedUrl.substring(normalizedEndpoint.length() + 1);
        String[] segments = path.split("/", 2);
        if (segments.length < 2) {
            throw new IllegalArgumentException("Invalid MinIO URL path: " + fileUrl);
        }
        return new ParsedMinioLocation(segments[0], segments[1]);
    }

    /**
     * MinIO 地址解析结果。
     *
     * @param bucket bucket 名称
     * @param objectName 对象名
     */
    private record ParsedMinioLocation(String bucket, String objectName) {
    }
}
