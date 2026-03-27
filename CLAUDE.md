
这份文档已经过全面重构与升维。它不仅是一份架构说明，更是整个系统工程的**宪法**。它明确了从宏观架构到微观设计模式、存储分工、以及系统与前端的契约。

请将以下内容直接保存为项目根目录下的 `CLAUDE.md`，然后交给 AI 编程助手（如 Antigravity / Claude Code / Cursor）开始生成代码。

---

## 0. 项目概述 (Project Overview)
本项目是一个基于 Java 生态（Spring Boot 4.0.5 + LangChain4j）的企业级 Agentic RAG（检索增强生成智能体）系统。
本系统的核心理念是**“渐进式能力叠加”**。它不仅提供传统的对话问答，更具备双模式 Agent 编排（ReAct & Plan-and-Execute）、分级上下文压缩、跨会话长期记忆、以及基于 MinerU 的高精度异构文档解析管道。

**核心存储规范（不可违背）：**
*   **MinIO**：负责所有物理文件的存储（原始 PDF/Word、解析后的庞大 Markdown 文件、提取的图片）。
*   **MySQL**：只存元数据指针（文档状态、MinIO URL、权限配置）和用户长期记忆（`user_global_memory`），绝对不存文件文本。
*   **Redis**：负责短期与中短期对话上下文缓存（Session Memory）和高频热点数据。
*   **ElasticSearch (8.x+)**：承载文本 Chunk 和 Dense Vector，执行混合检索。

---

## 1. Agent 编排与对话交互模块 [core]

**目标**：Agent 是系统的“大脑”，负责意图路由、步骤编排并与前端进行富媒体交互。本模块严格遵循“非确定性内容输出自然语言，确定性流程输出 JSON Schema”的原则。

*   **技术栈**：Spring Boot WebFlux (SSE), LangChain4j, JSON Schema (Jackson)
*   **双模式 Agent 编排机制**：
    1.  **ReAct 模式 (常规问答)**：基于 LangChain4j `AiServices`。大模型根据当前上下文，按照 `Thought -> Action (调用 Tool) -> Observation` 循环自主执行。
    2.  **Plan-and-Execute 模式 (复杂任务)**：针对宏大任务（如“对比A和B并生成报告”）。主 Agent 首先输出一个符合 JSON Schema 的**执行计划数组**（Todos），Java 后端解析该数组并迭代执行子任务（或交由 Sub-Agents 执行），最后汇总生成答案。
*   **前端接口预留与 SSE 契约 (Rich UI Rendering)**：
    系统提供统一的 WebFlux SSE 接口 `/api/v1/agent/chat/stream`。后端会向前端推送不同类型的 Event，前端据此渲染不同的 UI 组件：
    *   `event: plan_steps` -> 推送 JSON 数组，前端渲染为 **Todos 进度条**。
    *   `event: tool_call` -> 推送工具执行状态（如“正在检索知识库：2025财报”），前端渲染为加载动画。
    *   `event: message` -> 推送 Markdown 文本流，前端渲染为打字机对话。
    *   `event: citations` -> 推送 JSON 格式的溯源数组（包含 `doc_id`, `chunk_id`），前端渲染为**富文本引用卡片**。

## 2. RAG 核心引擎模块[core]

**目标**：提供企业级的高召回率检索能力，将 RAG 流程封装为标准的 `@Tool` 供 Agent 随时调用。

*   **技术栈**：ElasticSearch (8.x), Embedding API (BGE-Large), Reranker API (BGE-Reranker)
*   **配置参数 ( application.yml 静态可调)**：
    ```yaml
    rag:
      retrieval:
        knn-top-k: 20      # 向量 KNN 检索召回数
        bm25-top-k: 20     # 关键词 BM25 检索召回数
        rerank-top-n: 5    # 重排序后最终保留进入 Prompt 的 Chunk 数
    ```
*   **实现细节与流程**：
    *   **核心 Tool 封装**：定义 `@Tool("search_enterprise_knowledge")`，要求大模型必须传入 `query` 参数。
    *   **混合检索 (Hybrid Search)**：在 ElasticSearch 中通过 Java API 并发执行两路查询：向量相似度匹配 + BM25 全文检索。
    *   **RRF 融合与重排序**：将双路召回的结果（Top 20）使用倒数秩融合（Reciprocal Rank Fusion）合并，随后统一发送至独立的 Reranker 模型进行 Cross-Attention 交叉打分，截取 Top `rerank-top-n`。
    *   **Context 组装**：将这 Top 5 的 Chunk 组装成带有明确 `[Doc ID]` 标记的文本块，作为 Tool 的返回值（Observation）喂给 Agent。

## 3. 上下文与记忆管理模块 [core]

**目标**：突破 Token 窗口极限，实现上下文的分级压缩，并赋予智能体跨会话的“长期认知”。

*   **技术栈**：Redis (Session Memory), MySQL (Global Memory), LangChain4j 自定义 `ChatMemoryStore`
*   **分级上下文压缩 (Hierarchical Compression)**：
    实现自定义的对话拦截器与存储机制，依据对话轮数和 Token 消耗动态处理：
    *   **L1 (近程)**：最近 5 轮对话（原文保留，存 Redis）。
    *   **L2 (中程)**：第 6-15 轮对话。后台异步调用轻量级 LLM 将其总结为摘要（如“用户刚才探讨了系统架构图设计”）。
    *   **L3 (远程)**：15 轮以上对话，重度提炼，仅保留核心实体和最终结论。
*   **企业级长期记忆 (Long-Term Memory)**：
    *   **持久化介质**：MySQL `user_global_memory` 表（取代单机 `.md` 文件以支持分布式部署）。
    *   **自动提取**：提供 `@Tool("save_user_preference")`。Agent 发现用户偏好（如“我只看核心代码”、“用中文回复”）时自主调用该工具写入 MySQL。
    *   **生命周期**：每次新建 Session，拦截器自动读取该用户的长期记忆表，转化为 System Prompt 注入对话初始上下文中。

## 4. 异构文档处理与消息管道模块

**目标**：实现复杂企业文档（PDF/Word/TXT）的高吞吐解析入库，彻底解耦 Web 主干与耗时的解析引擎。

*   **技术栈**：Kafka, MinIO, Python (MinerU Worker), 设计模式 (Strategy, Factory)
*   **Kafka 消息队列设计**：
    *   `doc-parse-request`: Spring Boot 将上传到 MinIO 的文件 URL 放入此队列。Python 端的 MinerU Worker 消费并执行深度学习版面分析。
    *   `doc-vectorize-request`: Python 端将高精度 Markdown 存入 MinIO 后触发此队列。Spring Boot 监听并开始离线 Chunking。
    *   `doc-dlq` (死信队列): 失败超过 3 次的任务进入此队列，记录 MySQL `FAILED` 状态并报警。
*   **离线 Chunking 与设计模式**：
    为了未来优雅地兼容 TXT、DOCX 等格式，此处**必须**使用设计模式：
    *   **Strategy Pattern (策略模式)**：定义 `DocumentParserStrategy` 接口，下设 `MinerUMarkdownStrategy` (根据 Markdown 标题层级结合 Overlap 切分) 和 `StandardTxtStrategy`。
    *   **Factory Method (工厂模式)**：`DocumentParserFactory` 根据 MySQL 中的文件后缀动态组装并返回具体的策略执行类。

## 5. 权限控制与安全模块

**目标**：在数据物理层和逻辑层建立防线，彻底杜绝越权检索与 Prompt Injection 攻击。

*   **技术栈**：Spring Security, MySQL (RBAC), ElasticSearch Filters
*   **实现细节与流程**：
    *   **元数据打标**：所有切分后的文本块在写入 ES 时，强制挂载 `tenant_id` (租户)、`kb_id` (知识库 ID) 和 `allowed_roles` (允许访问的角色) 作为独立的 Keyword 字段。
    *   **检索拦截机制**：在 RAG `@Tool` 执行底层 ES 查询时，通过 Spring Security Context 提取当前登录用户的 ID 与 Role。将这些鉴权数据作为不可变的 `Filter` (Terms Query) 拼接在 ES 查询 DSL 中。即使大模型被恶意 Prompt 诱导去查询高管薪资文档，底层的 ES 也会在物理层面上返回 Empty Result。

---
## 给 AI 编程助手的开发指令：
1. **严格遵守职责分离**：不要在 Controller 层写业务逻辑；大模型调用和提示词组装必须封装在独立的 Service 或 LangChain4j 的 `AiServices` 接口中。RAG Agent Loop 核心编排不要全部依赖框架和Annotation，自己实现也不难，这是为了体现项目理解深度。
2. **面向契约编程**：前端 UI 需要的References溯源 (Citations) 和计划进度 (Todos)，必须使用 Jackson 生成/解析严格的 JSON Schema，绝对不要尝试用正则解析大模型的 Markdown 输出。LangChain4J应该是支持带上JSON Schema的。
3. **依赖注入**：充分利用 Spring 的 IoC 容器，所有的 Tool（如 `RagTool`）必须是 Bean，以便内部能够注入 ES Client 或 Mapper。
4. **日志规范**：在 Tool 被调用、Kafka 消息投递与消费、以及触发 L2/L3 记忆压缩时，必须使用 `log.info` 或 `log.debug` 打印关键追踪信息，方便链路排查。
5. System Prompt和JSON Schema不要零碎地散落在核心代码文件里，要保持代码整洁，遵守设计原则。