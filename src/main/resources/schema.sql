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

CREATE TABLE chat_session (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    session_id      VARCHAR(36)     NOT NULL UNIQUE COMMENT 'UUID v7，兼顾唯一性与时间排序',
    user_id         BIGINT          NOT NULL COMMENT '关联用户表',
    title           VARCHAR(200)    DEFAULT NULL COMMENT '会话标题，首轮对话后由 LLM 自动生成',
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED / DELETED',
    model_id        VARCHAR(64)     DEFAULT NULL COMMENT '该会话绑定的模型标识（可选）',
    message_count   INT             NOT NULL DEFAULT 0 COMMENT '消息计数器，用于触发 L2/L3 压缩',
    summary         TEXT            DEFAULT NULL COMMENT '会话级摘要（L3 压缩后的最终产物）',
    pinned          TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否置顶',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    archived_at     DATETIME(3)     DEFAULT NULL,

    INDEX idx_user_status_updated (user_id, status, updated_at DESC),
    INDEX idx_user_pinned (user_id, pinned DESC, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_message (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    message_id      VARCHAR(36)     NOT NULL UNIQUE COMMENT 'UUID',
    session_id      VARCHAR(36)     NOT NULL COMMENT '关联 chat_session.session_id',
    role            VARCHAR(16)     NOT NULL COMMENT 'user / assistant / system / tool',
    content         TEXT            NOT NULL COMMENT '消息正文（Markdown / JSON）',
    content_type    VARCHAR(16)     NOT NULL DEFAULT 'text' COMMENT 'text / tool_call / tool_result',
    token_count     INT             DEFAULT NULL COMMENT '该条消息估算 token 数，用于上下文窗口管理',

    -- 结构化元数据（JSON 列，而非打平为多列）
    metadata        JSON            DEFAULT NULL COMMENT '扩展字段：citations[], tool_name, 等',

    -- 压缩状态标记
    compression_level VARCHAR(4)    DEFAULT 'L1' COMMENT 'L1(原文) / L2(摘要) / L3(实体)',
    compressed_content TEXT         DEFAULT NULL COMMENT '压缩后的摘要文本（L2/L3 级别时填充）',

    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    INDEX idx_session_created (session_id, created_at ASC),
    INDEX idx_session_compression (session_id, compression_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;