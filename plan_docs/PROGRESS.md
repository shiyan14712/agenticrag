# Agentic RAG 项目实现进度

## 已实现 (Implemented)

- **底层架构**：升级 Maven `pom.xml`，指定 Spring Boot 版本 4.0.5 与 JDK 25 的预设，集成了依赖如 `langchain4j`、`spring-webflux`、`minio`、`elasticsearch-java`。
- **配置参数**：更新了 `application.yaml` 包含了真实环境中 MySQL 和 Redis 的连接信息（`10.60.31.253`），并为 Kafka、MinIO、Elasticsearch 预留了占位以便后续自行配置，也包含 RAG 检索可调参数。
- **Agent 编排与对话 (L1)**：创建了 `EnterpriseAgent` AI 代理接口以及暴露出响应式流 (`Flux<ServerSentEvent>`) 的 WebFlux Endpoint - `AgentController.java`。
- **Plan-and-Execute 双模式机制**：已实现独立接口 `/api/v1/agent/chat/plan-execute/stream` 和 `PlanAndExecuteOrchestrator`。基于 LangChain4j Json Schema 推导出 `ExecutionPlan` 及 `PlanStep` 实体，完成了按步骤下发、任务回调及合并总结，并支持向前端发送 `event: plan_steps` 和 `event: tool_call` 等。结合 JDK 25 的 `Virtual Threads`，彻底隔离了 WebFlux 的阻塞层。
- **RAG 核心工具封装 (L2)**：实现了 `RagTool` 并暴露 `@Tool("search_enterprise_knowledge")` 以模拟 ElasticSearch 混合检索。
- **离线文档解析管道设计模式 (L4)**：重构了 `DocumentParserFactory`，通过工厂模式基于扩展名下发至不同解析策略（如 MinerUMarkdownStrategy）。同时编写了 `DocumentMessageListener` 以处理 Kafka 的 `doc-vectorize-request` 及重试死信队列 (doc-dlq) 的监听。
- **上下文分级与长期记忆管理 (L3)**：自定义了 `CustomChatMemoryStore`，负责通过 Redis 缓存最近的 L1 轮次记忆；同时构建了 `UserGlobalMemory` JPA Entity，新会话开启时将从 MySQL 自动加载用户偏好作为 System Prompt。
- **ReAct 模式全局闭环与存储规约**：制定了 `storage.md` 并严格区分 MySQL、Redis、MinIO、ES 职责。在 `MySQL` 端规划了完整的 `user_global_memory` 与 `document_metadata` 表结构 (`schema.sql`)，并打通了从 Controller Header 到 Memory Provider 到数据库的调用闭环。

- **JSON Schema 全景约束架构落地**：
  - ✨在 `AgentController` 原有基础上执行重构设计，移除了业务代码，注入 `ChatOrchestrator` 以维持表现层与底层解耦。
  - ✨实现 Scene 4 路由网关约束：创建 `IntentRouterAgent` 与 `IntentDecision` DTO，基于 Schema 前置分流 `rag_search`、`complex_plan` 和 `small_talk`，避免沉重 RAG 无脑并发。
  - ✨实现 Scene 3 大模型的 Markdown 放权机制，流式下发 (`event: message`) 屏蔽 JSON 对话组件序列化延迟。
  - ✨实现 Scene 2 富媒体组件推送契约：建立 `Citation` Entity，在 SSE 流关闭时 (`onComplete`) 以独家信道挂载 JSON 对象 (`event: citations`)；追加支持外部纯拉式结构的 REST 接口 `@PostMapping("/chat/structured")` 以及 `RagStructuredResponse` 对象控制。
  
- **模型端点解耦配置 (LLM Endpoint Settings)**：
  - ✨在 `application.yaml` 增加了包括 LLM (Chat/Streaming)、Embedding 和 Reranker 模型在内的 URL / API-KEY 独立占位配置块。
  - ✨在 `src/main/java/com/yoswell/agenticrag/config/LlmConfig.java` 采用 `@Bean` 实例化了 `LangChain4j` 的 `ChatLanguageModel`, `StreamingChatLanguageModel` 引擎和 `EmbeddingModel` ，准备好与 `@AiService` 等声明式服务自动对接。
  
- **物理与逻辑层防线搭建 (Spring Security Tenant Authentication)**：
  - ✨新增了 `SecurityConfig` 预留基础授权链。
  - ✨开发了 `TenantAuthenticationFilter`，可通过 Header 解析 `X-Tenant-ID`、`X-User-ID` 并在 `SecurityContextHolder` 注入强类型的 `TenantUser` 身份令牌，阻止越权访问与 Prompt 注入。

- **高阶 RAG 搜索算法 (RRF & Rerank)**：
  - ✨在 `RagTool` 中真正引入了基于 Spring Security 提取 Tenant 后的检索参数拼接结构。
  - ✨实现了手动倒数秩融合算法 `calculateMockRrfFusion` 融合 BM25 和 KNN 两路的打分机制。
  - ✨实现了基于阈值截断（topK）和二阶排序（crossAttentionRerank）提取终态 Chunk，并拼接为携带 `[Doc ID]` 溯源块。

- **MinerU Worker 异构管道双向互通 (Kafka Integration)**：
  - ✨在原先的监听器之上，补充实现了 `DocumentMessageProducer`，利用 `KafkaTemplate` 可以把上传到 MinIO 的文件组装成结构化 JSON 抛入 `doc-parse-request` 主题中，指引 Python Worker 执行工作流。

- **领域网关驱动设计与 REST/SSE 混合接口群 (API Controllers Refactoring)**：
  - ✨新增 `api-design.md` 契约文档，全面审视富客户端前端所需触点。
  - ✨新增 `TaskController` 保障宏任务 (Plan-and-Execute) 可以跨网络重连：提供 `POST /task` 下发、`GET /task/{taskId}/stream` 挂载执行流、`GET /task/{taskId}/todos` 拉取全量状态机。
  - ✨新增 `SessionController` 下沉管理：提供 `GET /{sessionId}/history` 渲染历史气泡、及 `GET /user/memory` 触控 MySQL `user_global_memory` 偏好提取。
  - ✨新增 `DocumentController` 打通前端文件直传能力：承接物理落盘后，自动挂载并触发 `DocumentMessageProducer` 将分析指引推入系统主脉络。

- **会话管理与对话持久化 (Session Management)**：
  - ✨根据规范完整实现了 `ChatSession` 与 `ChatMessage` 的 Spring Data JPA 实体映射，并接入 MySQL 存储。
  - ✨实现 `SessionRedisManager` 缓存活跃会话及状态。
  - ✨全面重构 `SessionController` 且实现完整的 REST API 契约（包括会话创建、加载历史、会话切换以及软/硬删除）。
  - ✨实现 `SessionContextSwitcher` 会话切换以及 Spring ApplicationEvent (SessionCreatedEvent / SessionSwitchedEvent) 的无缝解耦。
  - ✨实现 `SessionTitleGenerator` 利用轻量 LLM 异步生成会话摘要标题。
  - ✨将 `ChatMessageService` 巧妙嵌入到原有 `ChatOrchestrator` 的双模式 SSE 流中，实现基于 Agent 输出全生命周期的无感日志拦截与消息入库统计机制。

- **WebFlux 响应式链路全面补全 (Reactive Pipeline Completion)**：
  - ✨**响应式调度器统一规约**：所有 Controller 中的 `Mono.fromCallable()` 和 `Mono.fromRunnable()` 均统一追加 `.subscribeOn(Schedulers.boundedElastic())`，将 MyBatis-Plus 阻塞型数据库调用正确卸载到弹性线程池，避免阻塞 Netty/Servlet 主线程。
    > 技术决策：由于项目使用 MyBatis-Plus 3.5.16（阻塞型 ORM），引入 R2DBC 不可行。正确做法是 `boundedElastic` 调度器卸载阻塞操作，SSE 流式端点则通过 `Flux.create()` + `Thread.startVirtualThread()` 实现完全非阻塞。
  - ✨**TaskController 伪实现消除**：三个端点（`POST /tasks`、`GET /tasks/{id}/stream`、`GET /tasks/{id}/todos`）全部接通真实的 `TaskService`，不再返回硬编码 Mock 数据。
    - `TaskService` 内部使用 `ConcurrentHashMap<String, TaskContext>` 管理活跃任务注册表。
    - `TaskContext` 持有 Reactor `Sinks.Many<SSE>.replay().all()` 广播通道，支持断线重连后获取全部历史事件。
    - 任务执行通过 JDK Virtual Thread 异步运行 Plan → Execute → Synthesize 三阶段流程。
    - `PlanStep` DTO 新增 `status` 字段（PENDING / IN_PROGRESS / DONE / FAILED），支撑前端 Todos 状态机渲染。
  - ✨**DocumentController 伪实现消除**：`POST /documents/upload` 和 `GET /documents/{id}/status` 全部接通真实业务逻辑。
    - 新增 `MinioConfig`：将 `application.yaml` 中 `minio.*` 参数注入为 `MinioClient` Bean。
    - 新增 `MinioService`：封装 MinIO SDK 阻塞操作（上传文件、桶自动创建），含完整的异常处理和日志追踪。
    - 新增 `DocumentService`：编排文档上传全链路管道（MinIO 存储 → MySQL 元数据持久化 → Kafka 消息触发），标注 `@Transactional` 事务边界。
    - `DocumentMetadata` 实体增加 `minioUrl` 和 `fileExtension` 字段，支撑从上传到向量化的全生命周期追踪。
    - `FilePart` 响应式流通过 `DataBuffer` 聚合 → `ByteArrayInputStream` 转换后交给阻塞 MinioService，正确处理了 `DataBufferUtils.release()` 防止内存泄漏。
  - ✨**SessionController 响应式修正**：所有 6 个端点的 `Mono.fromCallable()` / `Mono.fromRunnable()` 统一追加 `Schedulers.boundedElastic()` 调度器。
  - ✨**AgentController 一致性修正**：统一 `Schedulers` 导入风格，消除行内全限定名引用。
  - ✨**PlanAndExecuteOrchestrator 状态追踪**：在线执行模式下，每个 `PlanStep` 执行前后自动回写 `status` 状态（PENDING → IN_PROGRESS → DONE），与 TaskService 离线模式保持一致。


## 尚未实现或待完善 (Not yet implemented / Mocked)
- 探索更多关于 Elasticsearch 的实体索引与 LangChain4j 的 `Document` 类映射细节，建立真实的 Elasticsearch Mapping 和真实 Vector Ingestion。
- 搭建真实的 Python MinerU 消费 Worker，当前仅实现了 Java 侧的双向队列通信 (Producer & Consumer)。
- 基于 Docker Compose 构建 `db/redis/es/minio` 的基础设施本地测试环境。
- `RedisConfig` 中 `GenericJackson2JsonRedisSerializer` 在 Spring Boot 4.x 中已标记过时 (deprecated)，后续需替换为推荐的序列化方案。

---
*Date:* 2026-03-28
*Framework:* Spring Boot 4.0.5 | JDK 25
