-- 简化版：一租户一用户模式（企业号），User与Tenant概念合并，即 userId == tenantId
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL COMMENT '账号业务唯一标识（单租户模式下，逻辑上等同于 tenant_id）',
    username VARCHAR(128) NOT NULL COMMENT '企业账号登录名',
    password VARCHAR(255) NOT NULL COMMENT '密码的散列值',
    roles VARCHAR(512) DEFAULT NULL COMMENT '账号的角色列表（逗号分隔），可用于简单 RAG 拦截验证',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE, DISABLED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表（企业号）';

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
    document_id VARCHAR(128) NOT NULL COMMENT '业务文档ID，对外暴露的唯一追踪编号',
    tenant_id VARCHAR(128) NOT NULL COMMENT '多租户ID隔离（在简化一企业号模式下，可直接复用 user_id）',
    kb_id VARCHAR(128) NOT NULL COMMENT '所归属的逻辑知识库ID',
    file_name VARCHAR(255) NOT NULL COMMENT '原文件名',
    file_extension VARCHAR(32) NOT NULL COMMENT '文件扩展名，决定策略工厂的走向(如: md, pdf, txt)',
    minio_url VARCHAR(1024) NOT NULL COMMENT '指向 MinIO 的物理存储 URL',
    status VARCHAR(64) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态流转字典: UPLOADED, PARSING, VECTORIZED, FAILED',
    chunking_strategy VARCHAR(32) NOT NULL DEFAULT 'STANDARD' COMMENT '分块策略: STANDARD, DECONTEXTUALISED, QA_ENRICHED',
    allowed_roles VARCHAR(512) COMMENT '权限控制：逗号分隔的 Role 列表，存入 ES 用作拦截 Filter',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_id (document_id),
    INDEX idx_tenant_kb (tenant_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业文档元数据流水线跟踪表';

-- 新增: 消息可靠投递外箱表，记录待投递的 Kafka 消息及其状态，支持重试机制和幂等控制
CREATE TABLE IF NOT EXISTS mq_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbox_id VARCHAR(64) NOT NULL COMMENT '业务外箱消息ID',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '聚合类型，如 DOCUMENT',
    aggregate_id VARCHAR(128) NOT NULL COMMENT '聚合业务ID，如 document_id',
    task_id VARCHAR(64) DEFAULT NULL COMMENT '关联的异步任务ID',
    event_type VARCHAR(64) NOT NULL COMMENT '业务事件类型',
    topic VARCHAR(255) NOT NULL COMMENT '目标 Kafka topic',
    message_key VARCHAR(255) NOT NULL COMMENT 'Kafka message key',
    payload LONGTEXT NOT NULL COMMENT '序列化后的消息体',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, DISPATCHING, SENT, FAILED',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已失败重试次数',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许重试时间',
    sent_at DATETIME(3) DEFAULT NULL COMMENT '成功投递时间',
    last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次失败摘要',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_outbox_id (outbox_id),
    INDEX idx_outbox_dispatch (status, next_retry_at, id),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息可靠投递外箱表';

-- 新增: 文档异步任务账本表，记录文档相关的异步处理任务（如解析、向量化）的状态和日志，支持与外箱表关联追踪
CREATE TABLE IF NOT EXISTS document_async_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL COMMENT '异步任务业务ID',
    document_id VARCHAR(128) NOT NULL COMMENT '关联文档ID',
    tenant_id VARCHAR(128) NOT NULL COMMENT '租户ID',
    task_type VARCHAR(64) NOT NULL COMMENT 'DOCUMENT_PARSE, DOCUMENT_VECTORIZATION, DOCUMENT_DELETE',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, DISPATCHED, RUNNING, SUCCEEDED, FAILED, SKIPPED',
    topic VARCHAR(255) DEFAULT NULL COMMENT '关联 Kafka topic',
    message_key VARCHAR(255) DEFAULT NULL COMMENT 'Kafka message key',
    outbox_id VARCHAR(64) DEFAULT NULL COMMENT '关联外箱消息ID',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
    last_message_id VARCHAR(255) DEFAULT NULL COMMENT '最近一次消费/投递的消息ID',
    last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次错误摘要',
    started_at DATETIME(3) DEFAULT NULL COMMENT '任务开始时间',
    completed_at DATETIME(3) DEFAULT NULL COMMENT '任务结束时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_document_async_task_id (task_id),
    UNIQUE KEY uk_document_async_task_doc_type (document_id, task_type),
    INDEX idx_document_async_task_tenant_status (tenant_id, status, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档异步任务账本表';

-- 新增: 消息消费日志表，记录每次 Kafka 消息的消费尝试、状态和结果，用于幂等控制、监控和故障排查
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    consumer_group VARCHAR(255) NOT NULL COMMENT '消费者组',
    topic VARCHAR(255) NOT NULL COMMENT '消费 topic',
    message_identity VARCHAR(255) NOT NULL COMMENT '业务幂等消息标识',
    message_key VARCHAR(255) DEFAULT NULL COMMENT 'Kafka message key',
    payload_hash VARCHAR(64) NOT NULL COMMENT '消息体哈希',
    task_id VARCHAR(64) DEFAULT NULL COMMENT '关联异步任务ID',
    document_id VARCHAR(128) DEFAULT NULL COMMENT '关联文档ID',
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING, SUCCEEDED, FAILED, SKIPPED',
    consume_count INT NOT NULL DEFAULT 1 COMMENT '消费尝试次数',
    locked_until DATETIME(3) DEFAULT NULL COMMENT '处理中租约到期时间',
    started_at DATETIME(3) DEFAULT NULL COMMENT '开始消费时间',
    completed_at DATETIME(3) DEFAULT NULL COMMENT '消费完成时间',
    last_error VARCHAR(1000) DEFAULT NULL COMMENT '最近一次失败摘要',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_mq_consume_identity (consumer_group, topic, message_identity),
    INDEX idx_mq_consume_status (topic, status, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息消费幂等与日志表';

CREATE TABLE IF NOT EXISTS chat_session (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,
    session_id      VARCHAR(36)     NOT NULL UNIQUE COMMENT 'UUID v7，兼顾唯一性与时间排序',
    user_id         VARCHAR(128)    NOT NULL COMMENT '关联用户表 sys_user.user_id',
    title           VARCHAR(200)    DEFAULT NULL COMMENT '会话标题，首轮对话后由 LLM 自动生成',
    status          TINYINT         NOT NULL DEFAULT 0 COMMENT '会话状态：0=ACTIVE, 1=ARCHIVED, 2=DELETED',
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

CREATE TABLE IF NOT EXISTS chat_message (
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
    INDEX idx_session_content_created (session_id, content_type, created_at ASC),
    INDEX idx_session_compression (session_id, compression_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 实体注册表：Special Chunking 阶段由 LLM NER 提取的文档级实体，用于去上下文化和 QA 增强
CREATE TABLE IF NOT EXISTS entity_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL COMMENT '关联文档ID',
    tenant_id VARCHAR(128) NOT NULL COMMENT '租户ID',
    mention VARCHAR(255) NOT NULL COMMENT '原文中的表述（如 Bird）',
    full_name VARCHAR(512) NOT NULL COMMENT '完整名称（如 California scooter sharing start-up Bird）',
    definition TEXT COMMENT '简要定义或描述',
    category VARCHAR(32) NOT NULL COMMENT '实体类别: PERSON, ORGANIZATION, LOCATION, ABBREVIATION, TERM, OTHER',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_entity_document (document_id),
    INDEX idx_entity_tenant (tenant_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档实体注册表（Special Chunking NER 产物）';
