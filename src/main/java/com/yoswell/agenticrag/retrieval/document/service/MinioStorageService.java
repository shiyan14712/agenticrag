package com.yoswell.agenticrag.retrieval.document.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

/**
 * 封装 MinIO 阻塞 I/O 操作。
 * 所有方法均为阻塞调用，调用方应在 boundedElastic 调度器上执行。
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:agenticrag}")
    private String defaultBucket;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 上传文件到 MinIO，返回文件的存储 URL。
     * 
     * @param objectName  对象名称（唯一键）
     * @param inputStream 文件输入流
     * @param size        文件大小（字节），-1 表示未知
     * @param contentType MIME 类型
     * @return MinIO 内部 URL（格式: minio://bucket/objectName）
     */
    public String uploadFile(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucketExists(defaultBucket);

            PutObjectArgs putArgs = PutObjectArgs.builder()
                    .bucket(defaultBucket)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();

            minioClient.putObject(putArgs);

            String minioUrl = endpoint + "/" + defaultBucket + "/" + objectName;
            log.info("File uploaded to MinIO: {}", minioUrl);
            return minioUrl;

        } catch (Exception e) {
            log.error("Failed to upload file to MinIO: {}", objectName, e);
            throw new RuntimeException("MinIO upload failed for: " + objectName, e);
        }
    }

    public byte[] readFile(String fileUrl) {
        ParsedMinioLocation location = parseLocation(fileUrl);
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(location.bucket())
                        .object(location.objectName())
                        .build())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to read file from MinIO: {}", fileUrl, e);
            throw new RuntimeException("MinIO read failed for: " + fileUrl, e);
        }
    }

    public String readUtf8String(String fileUrl) {
        return new String(readFile(fileUrl), StandardCharsets.UTF_8);
    }

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

    private record ParsedMinioLocation(String bucket, String objectName) {
    }
}
