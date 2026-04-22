# Agentic RAG 项目实现进度

## 已完成 (Implemented)

### 基础架构与配置
- 已完成 Spring Boot 4.0.5 + LangChain4j + MySQL + Redis + Kafka + MinIO + Elasticsearch 的基础工程集成。
- 已完成 LangChain4j `0.36.x -> 1.12.2` 大版本迁移，移除 `langchain4j-spring-boot-starter`（该 starter 当前不支持 Spring Boot 4.x）。
- 已完成 `application.yaml` 的真实环境接入配置，并补充了 Elasticsearch 鉴权占位字段、RAG 索引名和分层记忆参数。
- 已启用异步能力，`ObjectMapper` 统一使用 `findAndRegisterModules()`。

### LangChain4j 1.12.2 大版本迁移 (Spring Boot 4 兼容)
- 已将模型接口从 `ChatLanguageModel` / `StreamingChatLanguageModel` 迁移为 `ChatModel` / `StreamingChatModel`。
- 已移除 `@AiService` 注解自动注册路径，新增 `AiServiceConfig`，统一通过 `AiServices.create(...)` / `AiServices.builder(...)` 手动注册 Spring Bean。
- 已在手动装配时显式挂接 `ChatMemoryProvider` 与工具集合（`RagTool`、`PreferenceTool`），确保 ReAct 与分层记忆行为保持一致。
- 已完成 `TokenStream` API 迁移：`onNext/onComplete` 升级为 `onPartialResponse/onCompleteResponse`。
- 已完成分层记忆摘要调用迁移：`chatLanguageModel.generate(...)` 升级为 `chatModel.chat(...)`。
- 已执行并通过编译验证：`./mvnw.cmd -DskipTests compile`。

### Agent 编排与 SSE 对话
- 已完成 `AgentController` 与 `ChatOrchestrator` 的职责拆分，流式对话仍由统一 SSE 接口输出。
- 已完成意图路由与 `rag_search` / `small_talk` 分流。
- 已完成真实 citations 回传链路：RAG Tool 会产出结构化检索结果，编排层会在 `event: citations` 中把真实引用数组推给前端，并同步落库到聊天消息 `metadata`。

### RAG 检索核心
- 已完成 `RagTool` 的真实检索流程，不再使用纯 mock：
  - Embedding 向量生成
  - BM25 检索
  - KNN 向量检索
  - RRF 融合
  - Reranker 二次重排
  - Observation 组装为 `[Doc ID][Chunk ID]` 格式
- 已新增 Elasticsearch Chunk 索引模型与 `KnowledgeChunkIndexService`。
- 已补齐 Elasticsearch 连接配置，支持从 `spring.elasticsearch.*` 读取 URI / username / password / api-key。
- 已为 reranker 增加独立客户端，失败时会自动降级回融合结果顺序。

### Session 与记忆管理
- 已完成 `ChatSession` / `ChatMessage` 的持久化模型与基础会话管理接口。
- 已修正消息计数逻辑：用户消息与助手消息分别计数，不再在助手消息阶段一次性加 2。
- 已完成 `HierarchicalChatMemoryStore` 的真实分层记忆逻辑：
  - L1 近期消息存 Redis
  - L2 / L3 摘要异步生成
  - L2 / L3 摘要回写 Redis
  - L3 摘要回写 `chat_session.summary`
  - 消息级压缩结果回写 `chat_message.compressed_content`
- 已修正 `memoryId` 来源：当前必须把 `memoryId` 当作真实 `sessionId` 使用，再通过 `chat_session` 查询用户，不再允许按 `"userId_sessionId"` 字符串拆分。
- 已新增 `ChatMemoryProvider` 配置，确保 LangChain4j AI Service 实际使用自定义的分层记忆存储。

### 文档处理与 Kafka 管道
- 已完成文档上传到 MinIO、元数据写入 MySQL、投递 `doc-parse-request` 的链路。
- 已完成 `document_metadata` 的代码与 schema 对齐：
  - 补充 `document_id`
  - 补充 `kb_id`
  - 补充 `allowed_roles`
  - 状态统一为 `UPLOADED / PARSING / VECTORIZED / FAILED`
- 已将 Kafka 消息从松散 Map/字符串升级为显式 DTO：
  - `DocumentParseRequest`
  - `DocumentVectorizeRequest`
- 已将文档异步链路的 topic 与重试参数统一收口到 `DocumentKafkaProperties + application.yaml`，移除 Producer / Listener / Service 中散落的 topic 硬编码。
- 已新增统一 Kafka 基础配置：
  - `AckMode.MANUAL_IMMEDIATE`
  - `DefaultErrorHandler`
  - 指数退避重试
  - `doc-dlq` 死信路由
- 已为 Kafka producer 显式补齐基础可靠性参数：
  - `acks=all`
  - `enable.idempotence=true`
  - `enable-auto-commit=false`
- 已完成 `DocumentMessageListener` 的真实向量化消费流程：
  - 解析 Kafka JSON
  - 回读 MinIO 文件内容
  - 调用解析策略
  - 生成 embedding
  - 写入 Elasticsearch
  - 更新文档状态
  - `doc-dlq` 失败状态回写
- 已把 `DocumentVectorizationService` 的状态迁移拆到独立短事务服务中，修复“向量化失败后 `FAILED` 状态可能随长事务回滚丢失”的问题。
- 已补充向量化阶段的基础重复消费防护：
  - 文档已 `VECTORIZED` 时直接跳过
  - 抢占 `PARSING` 状态失败时检测并发/重复处理
  - 写 ES 前先按 `documentId + tenantId` 清理旧 chunk

### 文档解析策略
- 已把 `DocumentParserStrategy` 从 `void parse(String fileUrl)` 升级为返回标准化 `ParsedDocument` 结果。
- 已完成 `MinerUMarkdownStrategy` 的真实 Markdown 分块实现，支持标题层级切分与 overlap。
- 已完成 `StandardTxtStrategy` 的真实 TXT 分块实现。
- 已新增 `DocumentParseSource`、`ParsedDocument`、`ParsedDocumentChunk` 等共享解析契约。

### 测试与验证
- 已新增针对本轮改动的测试，覆盖：
  - RAG 检索与权限参数传递
  - reranker 降级
  - 文档向量化链路
  - 分层记忆压缩
  - SSE citations 推送
- 已执行并通过：
  - `./mvnw -q -DskipTests compile`
  - `./mvnw -q test`

## 本轮新增 (2026-03-28)

- 完成真实 RAG 检索与 citations 回传，移除 `ChatOrchestrator` / `RagTool` 中与 mock citations、mock RRF、mock rerank 相关的占位逻辑。
- 完成 `DocumentVectorizationService` 与 `KnowledgeChunkIndexService`，使 `doc-vectorize-request` 不再只是日志监听。
- 完成 `StandardTxtStrategy` 的实现，并将解析策略统一为“输入 `DocumentParseSource`，输出 `ParsedDocument`”。
- 完成分层记忆摘要生成、Redis/MySQL 双落点和 `ChatMemoryProvider` 挂接。
- 清理并对齐了配置、元数据 schema 与消息契约，补上了之前阻塞真实实现的公共底座。

## 本轮新增 (2026-03-31)

- **Agent 重构 (ReAct 模式升级)**：
  - 彻底移除了原先充当硬编码路由守卫的 `IntentRouterAgent` 及关联意图对象 `IntentDecisionDTO`。
  - 将 `ChatOrchestrator` 的核心链路改造为纯 ReAct 模式。不再通过代码 `if/else` 判断是否调用 RAG 检索，而是将所有的 `@Tool`（如 `RagTool`、`PreferenceTool`）代理给大模型自主判断（该阶段最初基于 `@AiService` 注解实现，已在 2026-04-04 迁移为 `AiServices.builder(...)` 手动装配）。
  - 完善了 `ChatOrchestrator` 的虚拟线程上下文拦截，在 Agent 触发隐式 Tool Calling 时优雅地提取 Citations 引用，并推送到前端 SSE 事件。
- **虚拟线程异步生成会话标题**：
  - 修复了 LLM 的 Prompt Hijacking 漏洞（对用户输入进行 `{{it}}` 沙箱包裹与规范约束）。
  - 在 `ChatOrchestrator` 中引入了 `ChatService` 和 `ChatSessionMapper`，并在首条消息时通过 `Thread.startVirtualThread()` 异步非阻塞生成并持久化会话标题。
  - 完善了 `Mono.flatMap()` 处理嵌套 `Mono<Mono<String>>` 的问题，实现了 WebFlux SSE 接口的调优。

## 尚未完成 / 待完善 (Not Yet Implemented)

### I/O 线程模型优化
- 当前已切换到 WebMVC + Virtual Threads，数据库、MinIO、Elasticsearch、LangChain4j 仍属于阻塞 I/O 依赖，需要持续评估连接池与线程并发上限配置。
- 对于长时间 SSE 会话，需要进一步完善心跳、超时回收与断连恢复策略，降低高并发下的资源占用风险。

### Python MinerU Worker
- Java 侧的 `doc-parse-request` / `doc-vectorize-request` 契约已经明确，但 Python Worker 本身仍未在本仓库内实现。
- 当前默认约定是：Python 端完成高精度 Markdown 产出后，按 `DocumentVectorizeRequest` JSON 结构投递回 Kafka。
- 下一步需要让 Python 端显式透传 `taskId / messageId`，这样 `doc-parse-request -> doc-vectorize-request` 才能完成跨系统的可靠投递与消费账本闭环。

### 基础设施与部署
- 本地 Docker Compose 开发环境仍未补齐。
- Elasticsearch 若启用鉴权，仍需在 `application.yaml` 中补上真实的 `spring.elasticsearch.username/password/api-key`。

### 待办
- MinerU 链路接入
- Outbox / `document_async_task` / `mq_consume_log` 的查询、重放与人工补偿接口
- citations 目前回传的是本轮检索结果的聚合视图；如果未来出现多工具、多轮检索交错，需要考虑更稳定的会话级检索上下文传播机制。
- 当前记忆摘要为“生成式摘要”实现，后续可继续补 token 预算、摘要版本管理和更加细粒度的 L2/L3 触发条件。
- 会话消息缓存闭环（`session:messages:*`）尚未落地：当前仅定义 key 并在清理时删除，读取路径仍直连数据库。

### 下一步执行（会话消息缓存）
- 在 `SessionRedisManager` 增加消息缓存专用接口：`getSessionMessagesOrFallback(...)`、`cacheSessionMessages(...)`、`invalidateSessionMessagesCache(...)`。
- 在 `SessionService#getSessionMessages` 实现 Redis-first 读取策略：先查缓存，未命中回源 DB，命中后回填并设置 TTL。
- 在 `ChatMessageService` 的消息写入路径增加缓存失效：用户消息/助手消息入库后按 `sessionId` 失效对应消息缓存。
- 规范 key 设计：将 `sessionId + page + size` 纳入 key，避免分页串读。
- 增加测试：覆盖缓存命中、未命中回源、写后失效、分页 key 隔离四类场景。

---

*Date:* 2026-03-28  
*Framework:* Spring Boot 4.0.5 | LangChain4j | MyBatis-Plus | Kafka | MinIO | Elasticsearch

## 本轮新增 (2026-04-01)

- **WebMVC 与虚拟线程重构**：
  - 在 `application.yaml` 启用 `spring.threads.virtual.enabled=true`。
  - 彻底移除了 WebFlux 的 Reactor (Mono/Flux) 外壳包装，全面转向传统的同步方法和普通对象返回。
  - 对于大模型的流式返回，改用 WebMVC 下的 `SseEmitter` 配合虚拟线程执行，避免了之前繁琐的 `flatMap` 嵌套。
  - 将 `TenantAuthenticationFilter.java` 和 `SecurityConfig.java` 从 `WebFilter` 体系恢复到了经典的基于 `OncePerRequestFilter` 以及 `SecurityContextHolder.getContext()` 的线程级鉴权方式。

## 本轮新增 (2026-04-01, 第二轮)

- **架构一致性收口**：
  - 清理了代码中的残留响应式术语描述（`TenantUser`、`UserServiceImpl` 注释）。
  - 对齐了 `CLAUDE.md` 与 `zero_trust_tenant_isolation.md` 中的安全链路描述，统一为 Servlet 安全过滤链与无状态 JWT 模型。
  - 重写 `webflux_security_migration.md`，明确记录 WebFlux -> WebMVC 的回迁原因与改造项。
- **JWT 稳定性补强**：
  - `SecurityConfig` 明确配置 `SessionCreationPolicy.STATELESS`。
  - 修复登出场景 Bearer Token 解析，确保 Redis 中 Access Token 正确失效。

## 本轮新增 (2026-04-04, LangChain4j 1.12.2 大版本迁移)

- **依赖层迁移**：
  - 将 LangChain4j 版本升级至 `1.12.2`。
  - 移除 `langchain4j-spring-boot-starter`，改为显式依赖 `langchain4j` 与 `langchain4j-open-ai`。
- **模型层 API 迁移**：
  - 将 `LlmConfig` 中返回类型切换为 `ChatModel` / `StreamingChatModel`。
  - 保留 OpenAI/Doubao embedding 双提供方配置，兼容当前 provider 切换策略。
- **AI Service 装配迁移**：
  - 新增 `AiServiceConfig` 统一注册 `SimpleChatAgent`、`EnterpriseAgent`、`RagStructuredAgent`。
  - `EnterpriseAgent` 挂接流式模型、`ChatMemoryProvider` 与工具集，维持现有 ReAct 能力。
  - `RagStructuredAgent` 挂接非流式模型、`ChatMemoryProvider` 与 `RagTool`，维持结构化问答链路。
- **调用链兼容修复**：
  - `TokenStream` 回调迁移到 `onPartialResponse/onCompleteResponse`，恢复 WebMVC SSE 流式输出。
  - `HierarchicalChatMemoryStore` 摘要生成调用升级到 `chatModel.chat(...)`。
- **验证结果**：
  - 已执行 `./mvnw.cmd -DskipTests compile`，编译通过（BUILD SUCCESS）。

## 本轮新增 (2026-04-04, ReAct 过程可观测性)

- **问题诊断**：
  - 发现 `ChatOrchestrator` 的 SSE 事件流仅推送 `message`（最终回答 token）和 `citations`（引用卡片），ReAct 循环的 Thought → Action → Observation 中间过程对前端完全不可见。
  - 前端只能看到一段沉默期之后突然开始流式返回最终答案，无法呈现"正在思考"、"正在检索知识库"等交互动画。
- **LangChain4J 1.12.2 `TokenStream` 回调链利用**：
  - 完整挂载了 5 个生命周期回调：`onPartialThinking`、`beforeToolExecution`、`onToolExecuted`、`onPartialResponse`、`onCompleteResponse`。
  - `onPartialThinking`：接收 `PartialThinking` 对象（非 String），提取 `.text()` 推送 `event: thinking`。
  - `beforeToolExecution`：接收 `BeforeToolExecution` 对象，提取工具名和参数摘要，推送 `event: tool_start` + `ToolEventDTO` JSON。
  - `onToolExecuted`：接收 `ToolExecution` 对象，计算执行耗时，推送 `event: tool_result` + `ToolEventDTO` JSON。
  - `onPartialResponse`：保留原有的最终回答 token 推送。
  - `onCompleteResponse`：保留原有的 citations 推送 + 消息持久化，新增 `event: done` 结束信号。
- **新增文件**：
  - `SseEventType.java`：SSE 事件类型枚举，统一管理 7 种事件名常量（thinking / tool_start / tool_result / message / citations / done / error）。
  - `ToolEventDTO.java`：工具事件 JSON 载荷 record，提供 `executing()` / `completed()` / `failed()` 三个工厂方法。
- **不再需要 `ToolExecutionEventBus`**：
  - 原 plan 中为 0.36.x 设计的 EventBus 方案，因升级到 1.12.2 后框架原生提供 `beforeToolExecution` 回调，不再需要手动侵入 `@Tool` 方法推送事件。
- **设计文档同步**：
  - 更新 `CLAUDE.md` 中 Agent 编排模块的 SSE 契约描述，从旧的 3 事件模型（tool_call / message / citations）升级为完整的 7 事件 ReAct 可观测性协议。
- **架构决策说明**：
  - `thinking` 事件是否有输出取决于 LLM 后端（qwen3.5-122B）是否支持 reasoning token；若不支持，框架不会触发该回调，不影响其他功能。
  - 支持多 Tool 连续调用场景（如 LLM 先调 `search_enterprise_knowledge` 再调 `save_user_preference`），前端会收到多对 `tool_start → tool_result` 事件。
- **验证结果**：
  - 已执行 `./mvnw.cmd -DskipTests compile`，编译通过（BUILD SUCCESS）。

## 本轮新增 (2026-04-07, Kafka 配置收口与可靠性第一阶段)

- **Kafka 配置收口**：
  - 新增 `DocumentKafkaProperties`，将文档异步链路的 topic、DLQ 与重试参数统一绑定到 `agenticrag.kafka.*`。
  - 新增 `KafkaConfig`，集中定义文档链路的 listener container factory 和 error handler。
  - 移除 `DocumentMessageProducer`、`DocumentMessageListener`、`DocumentService` 中的 topic 硬编码字符串。
- **消费确认语义显式化**：
  - `DocumentMessageListener` 监听器升级为接收 `ConsumerRecord + Acknowledgment`。
  - 文档向量化与删除消费链路切换为 `MANUAL_IMMEDIATE` 手动 ack，只有业务处理成功后才提交消费确认。
  - 死信消费也统一走显式 ack，避免再次依赖隐式默认行为。
- **Kafka 可靠性基础补强**：
  - `application.yaml` 中为 producer 补充 `acks=all`、`enable.idempotence=true`、`max.in.flight.requests.per.connection=5`、`delivery.timeout.ms`。
  - consumer 明确关闭 `enable-auto-commit`。
  - 接入 `DefaultErrorHandler + ExponentialBackOffWithMaxRetries + DeadLetterPublishingRecoverer`，将重试耗尽的消息路由到 `doc-dlq`。
- **向量化状态与幂等修复**：
  - 新增 `DocumentProcessingStateService`，专门负责文档状态短事务迁移。
  - `DocumentVectorizationService` 不再把长耗时 I/O 包在单个事务里，失败时会独立回写 `FAILED` 状态。
  - 新增基础重复消费防护：文档已终态或已被其他消费者推进到 `PARSING` 时安全跳过。
  - 写 ES 前先删除同文档旧 chunk，降低重跑后残留过期分块的风险。
- **当前边界说明**：
  - 这一轮还没有落地事务型 Outbox、`document_async_task`、`mq_consume_log` 等完整可靠投递账本。
  - 上传与删除链路目前仍属于“发送可靠性初步补强”，尚未完成“数据库提交与消息投递最终一致性”的闭环。
- **验证结果**：
  - 已执行 `./mvnw.cmd -q -DskipTests compile`，编译通过。

## 本轮新增 (2026-04-07, Kafka 可靠性第二阶段账本闭环)

- **事务型 Outbox 落地**：
  - 新增 `mq_outbox` 表及对应的 entity / mapper / service，补齐消息投递账本。
  - 文档上传与文档删除链路已改为：事务内写业务数据 + 写 `mq_outbox`，不再直接在事务中调用 Kafka Producer。
  - 新增 `DocumentOutboxDispatcher`，通过定时轮询扫描 `PENDING / FAILED` 的 outbox 记录并异步投递，发送成功后回写 `SENT`，失败则记录 `retry_count / next_retry_at / last_error`。
  - 新增 `agenticrag.kafka.outbox.*` 配置项，并在应用入口启用 `@EnableScheduling`。
- **文档异步任务账本落地**：
  - 新增 `document_async_task` 表及 `DocumentAsyncTaskService`，将 `DOCUMENT_PARSE / DOCUMENT_VECTORIZATION / DOCUMENT_DELETE` 纳入统一异步任务生命周期管理。
  - 上传阶段会为 `DOCUMENT_PARSE` 创建任务；删除阶段会为 `DOCUMENT_DELETE` 创建任务；Java 侧消费 `doc-vectorize-request` 时会补齐/创建 `DOCUMENT_VECTORIZATION` 任务。
  - 任务状态已接入 `PENDING / DISPATCHED / RUNNING / SUCCEEDED / FAILED / SKIPPED` 的真实推进，便于排障、补偿与后续查询接口扩展。
- **消费日志、防重与幂等落地**：
  - 新增 `mq_consume_log` 表及 `MqConsumeLogService`，按 `consumerGroup + topic + messageIdentity` 建立消费幂等与日志记录。
  - `DocumentMessageListener` 已在 `doc-vectorize-request`、`doc-delete-request`、`doc-dlq` 三条链路接入 claim 逻辑：已成功/已跳过的消息会直接 ack；仍在处理中的消息不会被并发消费者重复执行。
  - 消息幂等键优先使用显式 `messageId`，缺失时回退到 `messageKey + payload hash`，兼容当前外部 Worker 尚未全部升级契约的阶段性现实。
- **向量化消费协同改造**：
  - `DocumentVectorizeRequestDTO`、`DocumentParseRequestDTO`、`DocumentDeleteRequestDTO` 已补充 `taskId / messageId` 字段，用于跨链路追踪与幂等。
  - `DocumentVectorizationService#vectorize(...)` 现在会显式返回“成功 / 跳过”结果，而不是把所有分支都折叠成异常，避免 `document_async_task` 与 `mq_consume_log` 记账失真。
  - Java 消费 `doc-vectorize-request` 时，会在进入向量化前将对应的 `DOCUMENT_PARSE` 任务标记为已完成，并在缺失 `tenantId` 时回退查询 `document_metadata`，降低外部 parse worker 渐进升级期间的兼容风险。
- **当前边界说明（第二阶段后）**：
  - Java 侧已经完成“业务表提交 -> Outbox -> Kafka 投递 -> 消费 claim -> 任务/消费日志落账”的半闭环；但 `doc-parse-request` 的真正消费端仍在外部 Python MinerU Worker，不在本仓库内。
  - 若要完成跨系统全链路最终闭环，下一步需要让 Python Worker 透传并回写 `taskId / messageId` 到 `doc-vectorize-request`，而不是只保证 Java 侧后半程可靠。
  - 当前还没有提供 Outbox 重放、任务补偿、消费日志查询等管理面接口，后续可继续补管理后台或运维脚本。
- **验证结果**：
  - 已执行 `./mvnw.cmd -q -DskipTests compile`，编译通过。
