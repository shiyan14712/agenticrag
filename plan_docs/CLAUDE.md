这份文档不仅是一份架构说明，更是整个系统工程的**宪法**。它明确了从宏观架构到微观设计模式、存储分工、以及系统与前端的规约。

# Agentic Research

---
## 0. 项目概述 (Project Overview)
本项目是一个基于 Java21 生态（Spring Boot 4.0.5 + LangChain4j 1.12.2 + ElasticSearch + Kafka + Redis + MySQL + MinIO + MinerU + WebMVC + Virtual Threads）的个人研究型知识库系统。
本系统的核心理念是**渐进式能力叠加**。它不仅提供传统的LLM对话问答，更具备智能文档切块策略、 Agent 编排（ReAct）、分级上下文压缩、跨会话长期记忆、以及基于 MinerU 的高精度异构文档解析管道。

**核心存储规范，分工明确各司其职：**
*   **MinIO**：负责所有物理文件的存储（原始 PDF/Word、解析后的庞大 Markdown 文件、提取的图片）。
*   **MySQL**：存储文档meta_data元数据、充当消息投递前的Outbox、持久化用户全局偏好（`user_global_memory`），绝对不存文件文本。
*   **Redis**：负责用户登录状态快速校验，短期与中短期对话上下文缓存（Session Memory）和高频热点数据。
*   **ElasticSearch (8.x+)**：存储分块文本向量，承载BM25关键词检索和KNN语义检索，执行混合检索。

---

## 1. Agent 编排与对话交互模块 [core]

**目标**：Agent 是系统的“大脑”，负责意图路由、步骤编排并与前端进行富媒体交互。本模块严格遵循**非确定性内容输出自然语言，确定性流程输出 JSON Schema**的原则。

*   **技术栈**：Spring Boot WebMVC (Virtual Threads, SseEmitter), LangChain4j, JSON Schema (Jackson)
*   **单一 Agent 编排机制**：
    * **ReAct 模式 (常规问答)**：基于 LangChain4j `AiServices`。大模型根据当前上下文，按照 `Thought -> Action (可能调用 Tool) -> Observation -> ... -> finish` 循环自主执行。
    * **Spring Boot 4 + LangChain4j 1.12.2 装配基线**：不依赖 `langchain4j-spring-boot-starter` 与 `@AiService` 自动注册；必须在配置类中使用 `AiServices.builder(...)/AiServices.create(...)` 手动注册 Spring Bean，并显式挂接 `chatMemoryProvider`、`tools`。
*   **前端接口预留与 SSE 契约 (Rich UI Rendering / ReAct 过程可观测性)**：
    系统提供统一的 WebMVC (Servlet) SSE 接口 `/api/v1/agent/chat/stream`，配合虚拟线程使用 `SseEmitter` 异步推流。
    `ChatOrchestrator` 通过 LangChain4j 1.12.2 的 `TokenStream` 完整回调链（`onPartialThinking`、`beforeToolExecution`、`onToolExecuted`、`onPartialResponse`、`onCompleteResponse`）将 ReAct 循环的每个阶段实时暴露给前端：
    *   `event: thinking` -> 推送 LLM 的 CoT 推理 token 流（需要 LLM 后端支持 reasoning token 输出），前端渲染为**思考动画 + 打字机文本**。
    *   `event: tool_start` -> 推送 `ToolEventDTO` JSON，标记工具开始执行（如"正在检索知识库：虚拟线程调度机制"），前端渲染为**加载动画 + 工具名标签**。
    *   `event: tool_result` -> 推送 `ToolEventDTO` JSON，标记工具执行完成（如"检索到 5 条知识片段，耗时 320ms"），前端渲染为**完成卡片**。
    *   `event: message` -> 推送 Markdown 文本流，前端渲染为**打字机对话**。
    *   `event: citations` -> 推送 JSON 格式的溯源数组（包含 `doc_id`, `chunk_id`），前端渲染为**富文本引用卡片**。
    *   `event: done` -> 推送空 JSON `{}`，标记整个 ReAct 循环结束。
    *   `event: error` -> 推送 `{ code, message }` JSON，前端渲染为**错误提示**。

## 2. RAG 核心引擎模块[core]

**目标**：提供企业级的高召回率检索能力，将 RAG 流程封装为标准的 `@Tool` 供 Agent 随时调用。

*   **技术栈**：ElasticSearch (8.x), Embedding API (Qwen3-Embed-4B, 2048 dims), Reranker API (DashScope qwen3-vl-rerank)
*   **实现细节与流程**：
    *   **核心 Tool 封装**：定义 `@Tool("search_enterprise_knowledge")`，要求大模型必须传入 `query` 参数。
    *   **混合检索 (Hybrid Search)**：在 ElasticSearch 中通过 Java API 并发执行两路查询：向量相似度匹配 + BM25 全文检索。
    *   **RRF 融合与重排序**：将双路召回的结果（Top 20）使用倒数秩融合（Reciprocal Rank Fusion）合并，随后统一发送至独立的 Reranker 模型进行 Cross-Attention 交叉打分，截取 Top `rerank-top-n`。
    *   **Final Score Threshold 过滤**：对 Reranker 返回分数（或 Reranker 降级后的 RRF 分数）统一执行硬阈值过滤（`final-score-threshold: 0.5`）；低于阈值的候选强制丢弃，若全部被过滤则返回空结果并打 WARN 日志。该逻辑在 `RagTool.applyFinalScoreThreshold()` 中实现。
    *   **Context 组装**：将这 Top-k 的 Chunk 组装成带有明确 `[Doc ID]` 标记的文本块，作为 Tool 的返回值（Observation）喂给 Agent。
*   **工程落地补充（已实现约束）**：
    *   ElasticSearch 连接配置必须从 `spring.elasticsearch.uris / username / password / api-key` 读取，不允许在 Java Config 中写死 `localhost`。
    *   除了给 LLM 的 Observation 文本外，RAG 检索还必须同步生成结构化检索结果（`RetrievedChunk` / `CitationDto`），供 `ChatOrchestrator` 在 SSE 结束时推送 `event: citations`。
    *   Reranker 必须允许失败降级；当外部 reranker endpoint 不可用时，系统应回退到 RRF 融合后的顺序而不是整条链路报错中断。

## 3. Session 管理[core]

它是 Agent 对话流程的基础设施层——没有 Session，Agent 和 Memory 模块都无法正确工作。

*   **技术栈**: MyBatis-Plus3.5.16, MySQL, Redis

### 设计目标

为系统提供完整的**多会话生命周期管理**能力，使用户能够：

1. **创建新会话 (New Session)**，每次开启全新上下文。
2. **切换会话 (Switch Session)**，在不同话题间自由跳转，上下文互不污染。
3. **浏览历史对话 (History)**，支持分页加载过往完整对话记录。
4. **会话归档与删除 (Archive/Delete)**，支持软删除与逻辑归档。

### 活跃 Session 指针模型（Active Session Pointer）

为了保证多会话场景下的上下文稳定性，系统采用**一用户一活跃会话指针**模型：

1. **指针定义**：Redis 中 `user:active-session:{userId}` 存储当前用户的唯一 `sessionId`。它是“默认上下文指针”，不是会话列表容器。
2. **设计目的**：减少每次请求都强依赖前端显式传 `sessionId` 的复杂度；在对话链路中提供稳定的默认会话定位能力。
3. **生命周期触发点**：
    * 创建会话后，默认将新会话设为 active。
    * 主动切换会话时，更新 active 指针。
    * 当归档/删除的是当前 active 会话时，必须自动重选一个最新 `ACTIVE` 会话；若不存在可用会话，清空该指针（删除 Redis Key）。
4. **一致性约束**：
    * active 指针永远不能指向非 `ACTIVE` 状态会话。
    * Redis 只作为加速层；指针缺失、过期或命中无效会话时，必须允许 MySQL 回查与降级恢复。
5. **扩展边界**：当前模型不支持“一用户多 active（多端并行）”；若未来需要，应引入 `userId + clientId/deviceId` 维度，不可直接把单值 Key 改为 List。


### 给 AI 编程助手的补充指令

1. **Session 隔离性是铁律**：任何涉及消息读写的操作，在 Service 层必须校验 `session.userId == currentUserId`。这不是可选的——它是安全模型的一部分.
2. **消息持久化的事务边界**：用户消息写入 MySQL 和 Redis **必须在 Agent 执行前完成**（防止浏览器关闭后丢消息）。助手回复则在 SSE 流结束后持久化。两者不在同一个事务中。如果因为**网络波动、用户意外关闭连接**导致某个session找不回来，这是系统级别的严重问题。
3. **会话切换不可阻塞**：`SessionContextSwitcher` 中的旧会话持久化和 L2/L3 压缩必须异步执行（`@Async` 或线程池），切换操作本身应在 200ms 内返回响应。
4. **Redis 只是加速层**：所有 Redis 操作必须有 MySQL 降级路径。`SessionRedisManager` 的每个读方法都必须接受一个 `Supplier<T> fallback` 参数。
5. **异步任务防重复最佳实践 (如标题生成)**：会话标题等只需触发一次的增强特性，不要通过每次前端发来流式消息时轮询 DB (`getTitle() == null`) 判断。必须统一利用 Redis 的 `SETNX` (搭配合理的生命周期边界，如 24h) 作为状态位锁互斥，并在虚拟线程中异步操作。这能极大减轻长连接下发的阻塞可能性与 DB 压力。
6. **Active 指针维护是强约束**：在 `create/switch/archive/delete` 这些会话生命周期操作中，必须维护 `user:active-session:{userId}` 的一致性。严禁留下悬挂指针（指向已归档/已删除/不存在会话）。



## 4. 分层上下文与记忆管理模块 [core]

**目标**：突破 Token 窗口极限，实现上下文的分级压缩，并赋予智能体跨会话的“长期认知”。

*   **技术栈**：Redis (Session Memory), MySQL (Global Memory), LangChain4j 自定义 `ChatMemoryStore`
*   **分级上下文压缩 (Hierarchical Compression)**：
    实现自定义 `HierarchicalChatMemoryStore`，依据消息数量动态分层（配置收口在 `application.yaml`）：
    *   **L1 (近程)**：最近 10 条消息（约 5 轮对话）原文保留，存 Redis。对应配置 `memory.l1-limit: 10`。
    *   **L2 (中程)**：第 11-30 条消息（约第 6-15 轮）。后台异步调用轻量级 LLM 将其总结为摘要（如”用户刚才探讨了系统架构图设计”）。对应配置 `memory.l2-limit: 30`。
    *   **L3 (远程)**：第 31-40 条消息（约第 16-20 轮）以上，重度提炼，仅保留核心实体和最终结论。对应配置 `memory.max-messages: 40`。
*   **企业级长期记忆 (Long-Term Memory)**：
    *   **持久化介质**：MySQL `user_global_memory` 表（取代单机 `.md` 文件以支持分布式部署）。
    *   **自动提取**：提供 `@Tool("save_user_preference")`。Agent 发现用户偏好（如“我只看核心代码”、“用中文回复”）时自主调用该工具写入 MySQL。
    *   **生命周期**：每次新建 Session，拦截器自动读取该用户的长期记忆表，转化为 System Prompt 注入对话初始上下文中。
*   **工程落地**：
    *   `memoryId` 在当前工程中等价于真实 `sessionId`，绝对不要假设它是 `"userId_sessionId"` 拼接串；需要先查 `chat_session` 再拿到 `user_id`。
    *   LangChain4j 侧必须显式挂接 `ChatMemoryProvider`，确保通过 `AiServices.builder(...)` 注册的 AI Service 代理真正使用 `HierarchicalChatMemoryStore`，不能只定义 Store Bean 却没有被 AI Service 消费。
    *   L2/L3 压缩结果除了写 Redis 以外，还必须回写 `chat_message.compressed_content` 与 `chat_session.summary`，否则“分层记忆”无法在持久化层闭环。

## 5. 异构文档处理与消息管道模块

**目标**：实现复杂企业文档（PDF/TXT）的高吞吐解析入库，利用消息队列彻底解耦 Web 主干与耗时的解析引擎

*   **技术栈**：Kafka, MinIO, Python (MinerU Worker), 设计模式 (Strategy, Factory)
*   **Kafka 消息队列设计**：
    *   `doc-parse-request`: Spring Boot 将上传到 MinIO 的文件 URL 放入此队列。Python 端的 MinerU Worker 消费并执行深度学习版面分析。
    *   `doc-vectorize-request`: Python 端将高精度 Markdown 存入 MinIO 后触发此队列。Spring Boot 监听并开始离线 Chunking。
    *   `doc-delete-request`: 文档进入删除补偿链路时投递此队列，供下游组件删除 Elasticsearch 中的向量与分块。
    *   `doc-dlq` (死信队列): 失败超过 3 次的任务进入此队列，记录 MySQL `FAILED` 状态并报警。
*   **离线 Chunking 与设计模式**：
    为了优雅地兼容 TXT、MD 等不同格式，此处使用设计模式实现智能分流：
    *   **Strategy Pattern (策略模式)**：定义 `DocumentParserStrategy` 接口，下设 `MarkdownStrategy` (根据 Markdown 标题层级结合 Overlap 切分) 和 `StandardTxtStrategy` (针对纯文本的自然断点切分)；两类策略都在自然断点上结束并重新进入下一个 chunk，避免 overlap 从词中间开始。
    *   **Factory Method (工厂模式)**：`DocumentParserFactory` 根据文件后缀动态组装并返回具体的策略执行类。
*   **智能 Chunk 改写策略**：
    为提供高精度的召回效果，系统实现了两种智能改写 chunk 的策略：
    *   **去上下文化改写 (Decontextualised Chunk Enrichment)**：通过 LLM 将代词替换为具体指代对象、展开缩写简称、补全省略的关键信息，使每个 chunk 脱离原文也能被完全理解。
    *   **QA 增强改写 (QA-Enriched Chunk Enrichment)**：识别文本中的模糊单元（代词指代不明、缩写未展开、省略主语等），然后基于实体注册表和相邻上下文生成补充陈述句，附加到原始内容后形成增强版本。
*   **实体注册表构建 (Entity Registry Building)**：
    NER 是两种 Enrichment 改写策略的基础：
    *   **命名实体识别 (NER)**：`EntityRegistryBuilder` 通过 LLM 对每个 chunk 进行实体提取，识别 PERSON、ORGANIZATION、LOCATION、ABBREVIATION、TERM 等类型的实体，记录其提及形式、完整名称、定义和类别。
    *   **实体合并与去重**：使用 `merge` 策略处理同一实体的多次出现，优先保留更长的完整名称，确保实体信息的准确性。
    *   **持久化存储**：实体注册表存入 MySQL `entity_registry` 表，支持按 `documentId` 加载复用，避免重复 NER 计算。
    *   **JSON Schema 约束**：NER 输出严格遵循 JSON Schema，确保结构化数据的可靠性，失败时自动降级跳过该 chunk。
*   **工程落地与可靠性保障**：
    *   **Topic 语义统一管理**：通过 `DocumentKafkaTopic` 枚举统一管理 Topic 语义标识与默认值，业务代码通过枚举取值，避免散落的字符串访问。
    *   **显式 JSON DTO 契约**：`doc-parse-request`、`doc-vectorize-request`、`doc-delete-request` 必须是显式 JSON DTO，支持 `taskId / messageId` 透传；严禁发送松散 `Map` 或靠日志约定字段名。跨系统的 `doc-parse-request -> Python Worker -> doc-vectorize-request` 必须透传这两个字段，确保全链路追踪闭环。
    *   **手动 ACK 机制**：Spring Kafka 消费端使用统一的 `ConcurrentKafkaListenerContainerFactory`，显式启用 `AckMode.MANUAL_IMMEDIATE`；业务处理成功后再 `ack.acknowledge()`，禁止依赖默认自动提交 offset 的隐式行为。
    *   **统一错误处理**：文档消费链路配置统一的 `DefaultErrorHandler`、指数退避重试与 DLT 路由，避免把“抛异常 + 期待默认行为正确”当作可靠性方案。
    *   **事务型 Outbox 模式保证最终一致性**：对于上传、删除这类“数据库提交与消息投递必须最终一致”的链路，采用事务型 Outbox：事务内只允许写业务表与 `mq_outbox` / `document_async_task`，严禁在同一事务中直接发送 Kafka。`DocumentOutboxDispatcher` 通过定时任务扫描 `PENDING / FAILED` 状态的 Outbox 记录，发送成功后转 `SENT`，失败时按指数退避回写下一次重试时间。
    *   **Outbox 表结构设计**：`mq_outbox` 至少记录 `outbox_id / aggregate_type / aggregate_id / task_id / topic / message_key / payload / status / retry_count / next_retry_at / sent_at / last_error`，确保消息投递的可追溯性与可重试性。
    *   **业务任务状态机管理**：`document_async_task` 是文档异步动作的业务账本，覆盖 `DOCUMENT_PARSE / DOCUMENT_VECTORIZATION / DOCUMENT_DELETE` 三类任务，维护 `PENDING / DISPATCHED / RUNNING / SUCCEEDED / FAILED / SKIPPED` 完整生命周期，不允许只有 MQ 状态没有业务任务状态。
    *   **消费幂等性保证（三层防护）**：
        1. **第一层 - 消息身份唯一标识**：`mq_consume_log` 的唯一幂等键基于 `consumer_group + topic + message_identity`；`message_identity` 优先使用业务 `messageId`，缺失时回退为 `message_key + payload SHA-256 hash`，兼容外部 Worker 渐进升级。
        2. **第二层 - 分布式锁租约机制**：消费日志通过 `locked_until` 字段实现租约锁，区分“正在处理”和“已处理完成”，避免并发消费者同时抢到同一条消息。锁超时后自动释放，允许其他消费者接管。
        3. **第三层 - 状态机防重判断**：`DocumentMessageListener` 在消费前调用 `MqConsumeLogService.claim()` 进行三重校验：(a) 已存在 SUCCESS/SKIPPED 记录则直接返回 `AlreadyCompletedException` 并 ACK；(b) 处于 PROCESSING 且锁未过期则抛出 `RetryLaterException` 触发重试；(c) 首次消费或锁已过期则获取处理权限并更新锁租约。
    *   **选择性幂等接入**：`mq_consume_log` 只接入确实需要防重复消费和可靠留痕的链路（当前包括 `doc-vectorize-request`、`doc-delete-request`、`doc-dlq`），不对所有消息一刀切引入重型去重，平衡性能与可靠性。
    *   **向量化重复消费防护**：Java 侧消费 `doc-vectorize-request` 时做基础重复消费防护：在文档已是 `VECTORIZED` 状态或已被其他消费者抢占到 `PARSING` 状态时安全跳过，返回 `skipped=true` 结果，不允许同一文档被并发重复向量化。
    *   **短事务边界拆分**：`DocumentVectorizationService` 不将 MinIO 读取、embedding、ES 写入和状态迁移包裹在同一个长事务中；状态迁移拆到短事务边界处理，避免失败时 `FAILED` 状态随事务一起回滚。
    *   **ES 写入前清理旧数据**：写 ES 前先按 `documentId + tenantId` 清理旧 chunk，再写入新 chunk，确保重复消费或重跑时不会残留过期分块。
    *   **标准化解析结果返回**：`DocumentParserStrategy` 返回标准化解析结果（`ParsedDocument` + `ParsedDocumentChunk`），而非 `void parse(...)` 这种“只执行不返回”的接口，确保向量化链路稳定消费。
    *   **元数据模型一致性**：`document_metadata` 的代码模型与 schema 始终保持一致，至少包括 `document_id / tenant_id / kb_id / allowed_roles / status / minio_url / file_extension` 字段。

## 6. 权限控制与零信任安全模块

**目标**：在数据物理层和逻辑层建立防线，彻底杜绝越权检索与 Prompt Injection 攻击。

*   **技术栈**：Spring Security, MySQL (RBAC), ElasticSearch Filters
*   **多租户与数据隔离控制域 (Tenant Isolation Scope)**：
    系统必须在以下四个核心边界上严格执行租户 (`tenant_id`) 及用户 (`user_id`) 隔离：
    1.  **用户上传的原始文档 (User-uploaded Documents)**：任何针对实体文件的只读外链 (MinIO pre-signed URL) 或下载请求，必须前置校验文件元数据的 `tenant_id`。
    2.  **解析后分块与向量数据 (Parsed Markdown & Vector Chunks)**：MinerU 解析出的高精度文本一旦切分落入 ElasticSearch，每条 Chunk 必须携带归属标签。大模型 RAG 检索时必须强制带上租户 Filter，严防“数据穿透命中”其他公司财报。
    3.  **用户会话与聊天记录 (Session & Chat History)**：所有的 Agent 会话与历史消息读写，必须绑定个人 `user_id`。Redis 与 MySQL 中的持久化数据禁止出现未声明鉴权所有者的孤儿对象。
    4.  **长期记忆与偏好 (Long-Term Memory)**：Agent 提炼并写入 `user_global_memory` 表的数据只属于特定用户，防止不同用户的私人偏好与知识发生交叉污染。
*   **实现细节与流程**：
    *   **元数据打标**：所有切分后的文本块在写入 ES 时，强制挂载 `tenant_id` (租户)、`kb_id` (知识库 ID) 和 `allowed_roles` (允许访问的角色) 作为独立的 Keyword 字段。
    *   **检索拦截机制**：在 RAG `@Tool` 执行底层 ES 查询时，通过 Spring Security Context 提取当前登录用户的 ID 与 Role。将这些鉴权数据作为不可变的 `Filter` (Terms Query) 拼接在 ES 查询 DSL 中。即使大模型被恶意 Prompt 诱导去查询高管薪资文档，底层的 ES 也会在物理层面上返回 Empty Result。
    *   **统一异常与安全响应隔离规范 (Exception Isolation)**：
        1. **业务异常兜底**：`@RestControllerAdvice` (如 `GlobalExceptionHandler`) 仅负责处理路由到 Controller 层后的业务异常 (如 `BusinessException` 等)，不处理任何鉴权相关逻辑。
        2. **安全异常拦截**：所有因为过滤器链（如 JWT 验证）或接口权限不足导致的 401/403，必须通过在 `SecurityConfig` 中配置 `AuthenticationEntryPoint` 及 `AccessDeniedHandler` 来拦截。它应负责输出符合全局 JSON Schema 契约（如 `ApiResponse<Void>`）的数据并附带正确的 HTTP 状态码，保障前端联调时对异常响应反序列化结构的一致性期望。

## 7. API 设计与响应规范 (API Design & Response Specification)

**目标**：在 RESTful 语义与企业级前后端对接效率之间取得最佳平衡点（兼顾路由优雅与异常载体的稳定性）。

*   **核心开发规范**：
    1.  **坚持 RESTful 动词语义**：API 路由必须严格使用 `@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping` 描述对资源的操作，禁止因为“图省事”彻底退化成全 `POST` 的 RPC 风格（如反模式 `POST /delete_session`）。
    2.  **强制 HTTP 200 OK 承载业务响应**：所有的正常业务流转（包括**成功**以及**各种受控的 BusinessException 业务异常**），必须统一以 HTTP 200 状态码返回 `ApiResponse<T>`，依据内部的自定义 `code` 区分业务结果。
    3.  **严禁滥用 `@ResponseStatus`**：绝不允许在业务 Controller 上使用如 `@ResponseStatus(HttpStatus.NO_CONTENT)` (HTTP 204) 等状态码。这能防止底层 Servlet 容器遵循 HTTP 协议时丢弃 `ApiResponse` Body，导致前端接收不到详细的错误溯源。
    4.  **保留底层硬性拦截状态**：仅在网关层、认证层面的致命拦截（例如：未携带 Token 触发的 401 Unauthorized、越权触发的 403 Forbidden，或系统宕机 500）时，允许返回真实的 HTTP 原生错误码。

---
## 给 AI 编程助手的开发指令：
1. **严格遵守职责分离**：不要在 Controller 层写业务逻辑；大模型调用和提示词组装必须封装在独立的 Service 或 LangChain4j 的 `AiServices` 接口中。Spring Boot 4.x 下不要依赖 `@AiService` 注解自动装配，统一通过配置类手动注册 AI Service Bean。RAG Agent Loop 核心编排不要全部依赖框架和Annotation，自己实现也不难，这是为了体现项目理解深度。
2. **面向契约编程**：前端 UI 需要的References溯源 (Citations) 和计划进度 (Todos)，必须使用 Jackson 生成/解析严格的 JSON Schema，绝对不要尝试用正则解析大模型的 Markdown 输出。LangChain4J应该是支持带上JSON Schema的。
3. **依赖注入**：充分利用 Spring 的 IoC 容器，所有的 Tool（如 `RagTool`）必须是 Bean，以便内部能够注入 ES Client 或 Mapper。
4. **日志规范**：在 Tool 被调用、Kafka 消息投递与消费、以及触发 L2/L3 记忆压缩时，必须使用 `log.info` 或 `log.debug` 打印关键追踪信息，方便链路排查。
5. System Prompt和JSON Schema不要零碎地散落在核心代码文件里，要保持代码整洁，遵守设计原则。
