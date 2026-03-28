# Agentic RAG 项目实现进度

## 已完成 (Implemented)

### 基础架构与配置
- 已完成 Spring Boot 4.0.5 + LangChain4j + MySQL + Redis + Kafka + MinIO + Elasticsearch 的基础工程集成。
- 已完成 `application.yaml` 的真实环境接入配置，并补充了 Elasticsearch 鉴权占位字段、RAG 索引名和分层记忆参数。
- 已启用异步能力，`ObjectMapper` 统一使用 `findAndRegisterModules()`。

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
- 已完成 `DocumentMessageListener` 的真实向量化消费流程：
  - 解析 Kafka JSON
  - 回读 MinIO 文件内容
  - 调用解析策略
  - 生成 embedding
  - 写入 Elasticsearch
  - 更新文档状态
  - `doc-dlq` 失败状态回写

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

## 尚未完成 / 待完善 (Not Yet Implemented)

### 响应式链路
- 当前控制器层虽已使用 `Mono` / `Flux`，但数据库、MinIO、Elasticsearch、LangChain4j 主链仍然是阻塞式依赖包裹，并非真正的全链路非阻塞实现。
- 若后续要严格兑现 WebFlux 的响应式价值，需要评估 R2DBC、异步 ES Client、消息消费线程模型与 LangChain4j 流式回调的调度方式。

### Python MinerU Worker
- Java 侧的 `doc-parse-request` / `doc-vectorize-request` 契约已经明确，但 Python Worker 本身仍未在本仓库内实现。
- 当前默认约定是：Python 端完成高精度 Markdown 产出后，按 `DocumentVectorizeRequest` JSON 结构投递回 Kafka。

### 基础设施与部署
- 本地 Docker Compose 开发环境仍未补齐。
- Elasticsearch 若启用鉴权，仍需在 `application.yaml` 中补上真实的 `spring.elasticsearch.username/password/api-key`。

### 进一步增强项
- citations 目前回传的是本轮检索结果的聚合视图；如果未来出现多工具、多轮检索交错，需要考虑更稳定的会话级检索上下文传播机制。
- 当前记忆摘要为“生成式摘要”实现，后续可继续补 token 预算、摘要版本管理和更加细粒度的 L2/L3 触发条件。

---

*Date:* 2026-03-28  
*Framework:* Spring Boot 4.0.5 | LangChain4j | MyBatis-Plus | Kafka | MinIO | Elasticsearch
