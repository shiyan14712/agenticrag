# Agentic RAG 项目实现进度



## 已实?(Implemented)



- **底层架构**：升?Maven `pom.xml`，指?Spring Boot 版本 4.0.5 ?JDK 25 的预设，集成了依赖如 `langchain4j`、`spring-webflux`、`minio`、`elasticsearch-java`
- **配置参数**：更新了 `application.yaml` 包含了真实环境中 MySQL ?Redis 的连接信息（`10.60.31.253`），并为 Kafka、MinIO、Elasticsearch 预留了占位以便后续自行配置，也包?RAG 检索可调参数
- **Agent 编排与对?(L1)**：创建了 `EnterpriseAgent` AI 代理接口以及暴露出响应式?(`Flux<ServerSentEvent>`) ?WebFlux Endpoint - `AgentController.java`
- **RAG 核心工具封装 (L2)**：实现了 `RagTool` 并暴?`@Tool("search_enterprise_knowledge")` 以模?ElasticSearch 混合检索
- **离线文档解析管道设计模式 (L4)**：重构了 `DocumentParserFactory`，通过工厂模式基于扩展名下发至不同解析策略（如 MinerUMarkdownStrategy）。同时编写了 `DocumentMessageListener` 以处?Kafka ?`doc-vectorize-request` 及重试死信队?(doc-dlq) 的监听
- **上下文分级与长期记忆管理 (L3)**：自定义?`CustomChatMemoryStore`，负责通过 Redis 缓存最近的 L1 轮次记忆；同时构建了 `UserGlobalMemory` JPA Entity，新会话开启时将从 MySQL 自动加载用户偏好作为 System Prompt
- **ReAct 模式全局闭环与存储规?*：制定了 `storage.md` 并严格区?MySQL、Redis、MinIO、ES 职责。在 `MySQL` 端规划了完整?`user_global_memory` ?`document_metadata` 表结?(`schema.sql`)，并打通了?Controller Header ?Memory Provider 到数据库的调用闭环?

- **JSON Schema 全景约束架构落地**?  - ✨在 `AgentController` 原有基础上执行重构设计，移除了业务代码，注入 `ChatOrchestrator` 以维持表现层与底层解耦?  - ✨实?Scene 4 路由网关约束：创?`IntentRouterAgent` ?`IntentDecision` DTO，基?Schema 前置分流 `rag_search`、`complex_plan` ?`small_talk`，避免沉?RAG 无脑并发?  - ✨实?Scene 3 大模型的 Markdown 放权机制，流式下?(`event: message`) 屏蔽 JSON 对话组件序列化延迟?  - ✨实?Scene 2 富媒体组件推送契约：建立 `Citation` Entity，在 SSE 流关闭时 (`onComplete`) 以独家信道挂?JSON 对象 (`event: citations`)；追加支持外部纯拉式结构?REST 接口 `@PostMapping("/chat/structured")` 以及 `RagStructuredResponse` 对象控制?  

- **模型端点解耦配?(LLM Endpoint Settings)**?  - ✨在 `application.yaml` 增加了包?LLM (Chat/Streaming)、Embedding ?Reranker 模型在内?URL / API-KEY 独立占位配置块?  - ✨在 `src/main/java/com/yoswell/agenticrag/config/LlmConfig.java` 采用 `@Bean` 实例化了 `LangChain4j` ?`ChatLanguageModel`, `StreamingChatLanguageModel` 引擎?`EmbeddingModel` ，准备好?`@AiService` 等声明式服务自动对接?  

- **物理与逻辑层防线搭?(Spring Security Tenant Authentication)**?  - ✨新增了 `SecurityConfig` 预留基础授权链?  - ✨开发了 `TenantAuthenticationFilter`，可通过 Header 解析 `X-Tenant-ID`、`X-User-ID` 并在 `SecurityContextHolder` 注入强类型的 `TenantUser` 身份令牌，阻止越权访问与 Prompt 注入?

- **高阶 RAG 搜索算法 (RRF & Rerank)**?  - ✨在 `RagTool` 中真正引入了基于 Spring Security 提取 Tenant 后的检索参数拼接结构?  - ✨实现了手动倒数秩融合算?`calculateMockRrfFusion` 融合 BM25 ?KNN 两路的打分机制?  - ✨实现了基于阈值截断（topK）和二阶排序（crossAttentionRerank）提取终?Chunk，并拼接为携?`[Doc ID]` 溯源块?

- **MinerU Worker 异构管道双向互?(Kafka Integration)**?  - ✨在原先的监听器之上，补充实现了 `DocumentMessageProducer`，利?`KafkaTemplate` 可以把上传到 MinIO 的文件组装成结构?JSON 抛入 `doc-parse-request` 主题中，指引 Python Worker 执行工作流?

- **会话管理与对话持久化 (Session Management)**?  - ✨根据规范完整实现了 `ChatSession` ?`ChatMessage` ?Spring Data JPA 实体映射，并接入 MySQL 存储?  - ✨实?`SessionRedisManager` 缓存活跃会话及状态?  - ✨全面重?`SessionController` 且实现完整的 REST API 契约（包括会话创建、加载历史、会话切换以及软/硬删除）?  - ✨实?`SessionContextSwitcher` 会话切换以及 Spring ApplicationEvent (SessionCreatedEvent / SessionSwitchedEvent) 的无缝解耦?  - ✨实?`SessionTitleGenerator` 利用轻量 LLM 异步生成会话摘要标题?  - ✨将 `ChatMessageService` 巧妙嵌入到原?`ChatOrchestrator` 的SSE 流中，实现基?Agent 输出全生命周期的无感日志拦截与消息入库统计机制?



## 尚未实现或待完善 (Not yet implemented / Mocked)

- ⚠️ **WebFlux 响应式链路“伪实现?(Fake Reactive) 警告**：当前虽?Controllers 层暴露了 `Mono`/`Flux` 接口，但底层并未真正实现全链路非阻塞?  1. 数据库接入目前使用的是阻塞型?`MyBatis-Plus`/`JDBC`，代码中大量通过 `Mono.fromCallable()` 强行包装阻塞调用（未指定标准调度器或未使?R2DBC）?  2. `TaskController` ?`DocumentController` 的业务逻辑目前使用 `Mono.just()` 直接返回硬编码的 Mock 数据（例如假?MinIO URL、未接通的异步任务机等）。真正的非阻?I/O 管道和真实业务调度仍待补全！

- 探索更多关于 Elasticsearch 的实体索引与 LangChain4j ?`Document` 类映射细节，建立真实?Elasticsearch Mapping 和真?Vector Ingestion?- 搭建真实?Python MinerU 消费 Worker，当前仅实现?Java 侧的双向队列通信 (Producer & Consumer)?- 基于 Docker Compose 构建 `db/redis/es/minio` 的基础设施本地测试环境?

---

*Date:* 2026-03-28

*Framework:* Spring Boot 4.0.5 | JDK 25



- **全局架构重构 (Domain-Driven Directory Restructuring)**?  - ✨执行了系统级的包结构调整，将原先混杂的按照层及功能混编的目录重构为严谨?Bounded Context 顶层模块?  - ✨按 Clean Architecture 梳理?core (AI 大脑与编??etrieval (RAG 文档解析与向量库)、platform (业务状态、会话与离线任务调度)、web (暴露接口安全网关) 多个独立防腐层，根治了工具链与传?CRUD 并包的问题

