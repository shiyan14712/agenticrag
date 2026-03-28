# 核心 API 与 Controller 架构规划 (API Design)

基于 `CLAUDE.md` 对 ReAct、Plan-and-Execute 双模式、长短期记忆以及异构文档处理的定义，系统的控制层（Controller）绝不能仅仅是两个发聊天的接口。一个完整的 Agentic RAG 系统必须提供对状态机、存储池、长时任务的观测和干预触点。

我们规划拆分和扩充以下 RESTful/SSE 接口群，以满足企业级富客户端（Rich UI）的交互需求：

## 1. 核心对话与动态路由 (AgentController)
保留现有的能力，作为普通问答与轻量级 ReAct 的统一入口。
- `POST /api/v1/agent/chat/stream`: (现有) 根据用户输入动态路由意图（发往 ReAct 或 Plan），通过 SSE 推送 `message` / `citations`。
- `POST /api/v1/agent/chat/structured`: (现有) 非流式的 JSON 响应，直接抽取结构化结果。

## 2. 复杂任务与 Plan-and-Execute (TaskController)
复杂的 Plan-and-Execute 往往需要几十秒到几分钟，前端如果在执行期间刷新页面，必须能够通过 `taskId` 重新挂载监听，获取当前的 Todos 状态机进度。
- `POST /api/v1/agent/task`: 提交一个宏大/复杂任务，立刻返回分布式的 `taskId`，不阻塞主线程。
- `GET /api/v1/agent/task/{taskId}/stream`: 携带任务 ID 持续监听任务的执行流水（SSE），接收 `plan_steps` 和 `tool_call` 事件。
- `GET /api/v1/agent/task/{taskId}/todos`: (REST) 拉取某个任务当前的完整执行计划状态机快照（Pending / In-Progress / Done）。

## 3. 会话与记忆管理 (SessionController)
由 `CustomChatMemoryStore` 和 `UserGlobalMemory` 统管的记忆层，需要暴露给前端让用户拥有“记忆管理权”。
- `GET /api/v1/agent/session/{sessionId}/history`: 拉取当前 Session 在 L1（Redis）及 L2 摘要中的历史记录，用于前端渲染聊天气泡界面的初始化。
- `DELETE /api/v1/agent/session/{sessionId}`: 清除当前会话上下文。
- `GET /api/v1/agent/user/memory`: 拉取当前用户的长期偏好记忆（由 MySQL 的 user_global_memory 提供）。

## 4. 知识库与异构文档管道 (DocumentController)
对应系统架构中的 MinerU 解析管道。前端上传物理文件需要触发后端的处理流，而不能只是 RAG 搜索。
- `POST /api/v1/agent/document/upload`: 上传文件（PDF/Word等）至 MinIO，并由 Controller 触发 `DocumentMessageProducer` 向 Kafka 发送 `doc-parse-request` 消息。返回 `documentId`。
- `GET /api/v1/agent/document/{documentId}/status`: 轮询或查询特定文档的 MinIO 存储、以及 MinerU 异步向量化解析的状态（PROCESSING, COMPLETED, FAILED）。

---
**实施计划**：
1. 首先增加 `api-design.md` 作为契约约束（已完成）。
2. 在 `controller/` 包下补充创建 `TaskController`、`SessionController`、`DocumentController`，明确结构定义和模拟/关联调用的 Service 方法。
3. 审查补充 `AgentController`，确保核心入口的整洁。