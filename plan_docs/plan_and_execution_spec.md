# AgenticRAG: Plan-and-Execute 模式解决方案文档

## 1. 背景与动机
在企业级 Agentic RAG 系统中，面对复杂的宏大任务（如：“对比A产品和B产品在2024年的安全报告，结合财报归纳出三种趋势并输出带有评估雷达图的 Markdown”），传统的基于 `ReAct`（Reasoning and Acting）的逐步思维链模式容易陷入“上下文超载”、“执行循环死锁”以及“遗忘初始目标”等问题。

为彻底解决复杂任务拆解，系统引入了 **Plan-and-Execute (规划与执行)** 混合架构模式。此模式下，Agent 工作流解耦为三个核心阶段（规划、执行、合成），能大幅提升长程任务的任务成功率与可观测性。

## 2. 总体架构设计

在 Plan-and-Execute 模式中，我们的请求不会经过常规的 `ReAct` 大脑循环，而是交由专门的编排服务 `PlanAndExecuteOrchestrator` 来管理，该服务内部持有了三个核心代理：

### 2.1 三大核心协作 Agent
1. **PlannerAgent (规划专家)**: 
   - 职责：只阅读用户的任务 `Objective`，不执行任何工具。将宏大任务根据内在逻辑结构拆解为一系列严谨的 `PlanStep`（步骤流数组，符合强格式 JSON Schema）。
   - 产出：`ExecutionPlan`（包含 Steps: ["搜索A财报", "搜索B财报", "提取对比数据", "生成总结"]）

2. **ExecutorAgent (执行专家)**: 
   - 职责：从循环中承接单个子 `PlanStep` 和截至目前为止生成的 `ExecutionContext` 历史。该实体拥有全部的 `@Tool` 访问权限（如 RAG 检索、图表生成等），负责具体执行单一明确指令。
   - 产出：生成执行报告（`Result`）。随后由服务拼接并传递给下一个子步骤。

3. **SynthesizerAgent (合成专家)**:
   - 职责：当所有的 Step 都被成功执行后，该实体获取`用户原始目标` + `所有步骤的串联执行结果报告`。负责进行提炼、排版补全，最终以流式（Markdown Streaming）响应吐出给最终用户。

### 2.2 响应式流管道 (Reactive SSE Pipeline)
通过 Spring WebFlux，Plan-and-Execute 过程不再是传统的长阻塞轮询，而是向前端输出了丰富的多模态交互事件（ServerSentEvent）：

* `event: plan_steps` -> 当 PlannerAgent 生成任务，或某步骤完成时触发推送 JSON，供前端渲染**甘特图 / 进度条 (Todos)**。
* `event: tool_call` -> 在 ExecutorAgent 执行过程中推送当前正在执行的任务名称，供前端呈现“正在处理 xx”。
* `event: message` -> SynthesizerAgent 合成响应时的逐字 Markdown 吐出流。
* `event: error` -> 任务异常或彻底崩溃时的容错降级触点。

## 3. 请求交互端点映射

本架构对外不仅暴露独立的编排端点以承接新流，同时兼容长时异步系统形态：

**端点 1：流式同步接口 (Synchronous SSE Focus)**
`POST /api/v1/agent/chat/plan-execute/stream`
由前端在对话框内针对复杂指令显式触发，底层使用 JDK21+ Virtual Threads 将长耗时的模型 API 调用隔离出响应式主干，确保 SSE 推送即时到达不阻塞网络线程。

**端点 2：异步离线任务编排 (Asynchronous Task Manage)**
面向更极端的长时构建，提供 `POST /api/v1/tasks` 来派发后台任务，前端通过 `GET /api/v1/tasks/{taskId}/stream` 订阅状态流，或通过 `GET /api/v1/tasks/{taskId}/todos` 对被中断的状态（如重新加载页面后）进行重新水化 (Hydration)。

## 4. 实施状态与完善策略

目前核心组件代码已完成实现与注入，补齐了由于路由遗漏导致的功能阻断。
已有的组件包：
- `@RestController` - `AgentController` (映射接通)
- `@Service` - `PlanAndExecuteOrchestrator`
- `PlannerAgent`, `ExecutorAgent`, `SynthesizerAgent` (依赖注入完毕，LangChain4j 接口定义完成)

此解决方案文档将做为整个架构扩展的标准参照，确立后续任何需要使用子拆解模式开发高阶 Agent 能力（如多表自动报表拼接、离线宏大知识库自动校准等）的规范标尺。
