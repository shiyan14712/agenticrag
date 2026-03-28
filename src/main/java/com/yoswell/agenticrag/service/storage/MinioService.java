package com.yoswell.agenticrag.service.storage;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

/**
 * 封装 MinIO 阻塞 I/O 操作。
 * 所有方法均为阻塞调用，调用方应在 boundedElastic 调度器上执行。
 */
@Service
public class MinioService {

    private static final Logger log = LoggerFactory.getLogger(MinioService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name:agenticrag}")
    private String defaultBucket;

    @Value("${minio.endpoint}")
    private String endpoint;

    public MinioService(MinioClient minioClient) {
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
}
