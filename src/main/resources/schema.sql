-- 用户长期记忆表：由大模型在发现用户偏好时存入，或拦截器拉取作为 System Prompt
CREATE TABLE IF NOT EXISTS user_global_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL COMMENT '用户ID或租户下的唯一标识',
    preference_key VARCHAR(255) NOT NULL COMMENT '偏好或记忆的键名/主题',
    preference_value TEXT NOT NULL COMMENT '具体记录的长期偏好细节',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户跨会话级别长期记忆表';

-- 文档元数图架构指针表：仅存储状态、索引依据与 MinIO URL，绝不存储内容
CREATE TABLE IF NOT EXISTS document_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL COMMENT '多租户ID隔离',
    kb_id VARCHAR(128) NOT NULL COMMENT '所归属的逻辑知识库ID',
    file_name VARCHAR(255) NOT NULL COMMENT '原文件名',
    file_extension VARCHAR(32) NOT NULL COMMENT '文件扩展名，决定策略工厂的走向(如: md, pdf, txt)',
    minio_url VARCHAR(1024) NOT NULL COMMENT '指向 MinIO 的物理存储 URL',
    status VARCHAR(64) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态流转字典: UPLOADED, PARSING, VECTORIZED, FAILED',
    allowed_roles VARCHAR(512) COMMENT '权限控制：逗号分隔的 Role 列表，存入 ES 用作拦截 Filter',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_kb (tenant_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业文档元数据流水线跟踪表';
