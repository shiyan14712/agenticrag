package com.yoswell.agenticrag.retrieval.document.dto.request;

import java.util.List;

/**
 * 文档上传成功后投递到解析链路的消息体。
 *
 * <p>它描述的是“哪个文档需要继续进入解析流程”，因此只保留后续阶段所需的
 * 最小上下文，不直接携带文件二进制内容。</p>
 *
 * @param documentId 业务侧文档唯一标识
 * @param tenantId 文档所属租户
 * @param kbId 文档所属知识库
 * @param fileName 原始文件名
 * @param fileUrl 文件在对象存储中的地址
 * @param fileExtension 文件扩展名，用于选择解析策略
 * @param allowedRoles 文档允许访问的角色列表
 * @param taskId 关联的异步任务 ID
 * @param messageId 业务幂等消息 ID
 * @param timestamp 消息创建时间戳，用于链路追踪
 */
public record DocumentParseRequestDTO(
        String documentId,
        String tenantId,
        String kbId,
        String fileName,
        String fileUrl,
        String fileExtension,
        List<String> allowedRoles,
        String taskId,
        String messageId,
        long timestamp
) {
}
