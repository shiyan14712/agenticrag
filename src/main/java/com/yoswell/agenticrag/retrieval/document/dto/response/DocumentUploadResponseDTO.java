package com.yoswell.agenticrag.retrieval.document.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传响应 DTO
 *
 * <p>封装文档上传后的基本信息，包括业务 ID、处理状态和存储地址</p>
 * <p>上传成功仅表示任务已创建，后续解析/向量化由异步链路完成</p>
 *
 * @author AgenticRAG Team
 * @since 2026-04-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResponseDTO {

    /**
     * 文档业务唯一标识
     *
     * <p>用于后续查询状态、删除等操作</p>
     */
    private String documentId;

    /**
     * 当前处理状态
     *
     * <p>可能的值：UPLOADED（已上传）、PARSING（解析中）、VECTORIZED（已向量化）、FAILED（失败）</p>
     */
    private String status;

    /**
     * MinIO 存储地址
     *
     * <p>指向文件在对象存储中的物理位置</p>
     */
    private String minioUrl;
}
