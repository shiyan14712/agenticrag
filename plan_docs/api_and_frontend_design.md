# Agentic RAG 前端接口文档与设计规范

本文档基于 `CLAUDE.md` 的架构设计，梳理了后端的可用接口，并给出了与这些接口对接的前端设计原则（特别是大模型的 ReAct 流式问答场景）。

## 1. 核心业务接口 (API Reference)

系统坚持 RESTful 动词语义，所有通常的业务响应（包括受控的业务异常）均会返回 HTTP 200，并附带统一的 JSON Schema：
```json
{
  "code": "200", // 成功为 "200"，其他为业务错误码（如 USER_NOT_EXIST, INVALID_TOKEN 等）
  "message": "Success",
  "data": { ... } // 具体业务数据，成功时包含，失败时通常为 null
}
```

注意：`/api/v1/agent/chat/stream` 使用的是 Server-Sent Events (SSE) 协议不包裹常规的 `ApiResponse`。安全拦截（如 401 Unauthorized）和 500 系统异常会返回原生 HTTP 状态码。

### 1-1. 认证与授权模块 (/api/v1/auth)

所有接口统一前缀: `POST /api/v1/auth/*`

*   **用户注册**
    *   **接口**: `POST /register`
    *   **请求 Body**: `UserRegisterReqDTO` (包含 `username`, `password`, `confirmPassword`)
    *   **响应 Data**: `UserRegisterRespDTO` (包含 `userId`, `username`, `status`, `roles`)

*   **用户登录**
    *   **接口**: `POST /login`
    *   **请求 Body**: `UserLoginReqDTO` (包含 `username`, `password`)
    *   **响应 Data**: `UserLoginRespDTO` (包含双 Token: `accessToken`, `refreshToken` 及过期时间、用户信息)
    *   **前端处理建议**: 登录成功后，前端应在本地存储 (LocalStorage/SessionStorage) 这些 Token，在后续请求中将 `accessToken` 附加到 `Authorization: Bearer <token>` Header 中。

*   **Token 刷新**
    *   **接口**: `POST /refresh`
    *   **请求 Body**: `UserRefreshTokenReqDTO` (包含 `refreshToken`)
    *   **响应 Data**: 同 `UserLoginRespDTO` (签发新的 Access Token 和 Refresh Token)
    *   **前端处理建议**: Axios 拦截器捕获 401 响应时，应静默调用此接口尝试刷新 Token 并重发请求。

*   **用户登出**
    *   **接口**: `POST /logout`
    *   **请求 Header**: `Authorization` (传入当前的 Access Token)
    *   **请求 Body**: `UserLogoutReqDTO` (可选，包含 `refreshToken` 以在后端清理)
    *   **响应 Data**: 成功的消息字符串

### 1-2. 会话管理模块 (/api/v1/sessions)

会话管理是保持 Agent 对话隔离的核心，所有接口都需要在 Header 携带 Access Token (`Authorization: Bearer <token>`)。

*   **创建新会话**
    *   **接口**: `POST /api/v1/sessions`
    *   **请求 Body**: 可选 `SessionCreateRequestDTO` (可指定绑定的 `modelId`)。
    *   **响应**: HTTP 201 Created，返回 `ChatSession` 对象（重点包含生成的 `sessionId`）。

*   **查询用户会话列表**
    *   **接口**: `GET /api/v1/sessions?page=0&size=20&status=ACTIVE`
    *   **响应**: 返回由 MyBatis-Plus `Page<ChatSession>` 序列化的分页结构数据。

*   **查询指定会话历史记录**
    *   **接口**: `GET /api/v1/sessions/{sessionId}/messages?page=0&size=50`
    *   **响应**: 包含 `session` 实体和 `messages` (聊天消息记录的分页 `Page<ChatMessage>`)。

*   **激活系统会话**
    *   **接口**: `PUT /api/v1/sessions/{sessionId}/activate`
    *   **响应**: 激活后的 `ChatSession` 对象。后端会处理对应的旧会话清理和新会话激活。

*   **更新会话属性**
    *   **接口**: `PATCH /api/v1/sessions/{sessionId}`
    *   **请求 Body**: `SessionUpdateRequestDTO` (可以更新 `title` 会话标题，以及 `pinned` 是否置顶)。
    *   **响应**: 更新后的 `ChatSession` 对象。

*   **删除/归档会话**
    *   **接口**: `DELETE /api/v1/sessions/{sessionId}?mode=archive`
    *   **请求参数**: `mode` 默认为 `archive` 归档，传 `permanent` 为物理删除。
    *   **响应**: HTTP 204 No Content，为空响应体。

### 1-3. 文档处理模块 (/api/v1/documents)

针对租户文档（PDF/Word等）的处理管道，基于异步解析。

*   **上传知识库文档**
    *   **接口**: `POST /api/v1/documents/upload`
    *   **Content-Type**: `multipart/form-data`
    *   **请求 Body**: `file` 字段携带文件。
    *   **响应**: 返回新创立的 `documentId`，文档状态 `status` 以及其原始 `minioUrl`。该接口立刻返回，只代表上传完成。

*   **轮询查询状态**
    *   **接口**: `GET /api/v1/documents/{documentId}/status`
    *   **响应**: 返回文件处理进度和状态的 Map。

*   **获取租户所有文档**
    *   **接口**: `GET /api/v1/documents`
    *   **响应**: 包含 `DocumentDTO` (含 ID, 文件名，后缀和状态) 列表的数据。

*   **删除文档**
    *   **接口**: `DELETE /api/v1/documents/{documentId}`
    *   **响应**: 返回空响应。

### 1-4. 核心 Agent 对话交模块 (/api/v1/agent)

与智能体大模型的直接对话接口。注意这些接口需要特殊的传参。必须验证 `sessionId`。（注意必须在Header带上 `Authorization` 及 `X-Session-Id`）

*   **【核心】流式问答模式 (SSE)**
    *   **接口**: `POST /api/v1/agent/chat/stream`
    *   **Request Header**:
        *   `Authorization: Bearer <token>`
        *   `X-Session-Id: <sessionId>` （前端必须先调用 `/api/v1/sessions` 创建出 sessionId）
    *   **Content-Type**: `text/plain` 或 `application/json`。
    *   **响应类型**: `text/event-stream` （Server-Sent Events）。
    *   **数据结构**: 将推送以下种类的 `event`:
        *   `tool_call`: 发送工具执行状态提示。
        *   `message`: 发送回答内容，用作打字机更新。
        *   `citations`: 发送 `CitationDTO` 原生的 JSON 数据。用于提供富媒体的检索文章片段和分数参考。

*   **结构化一次性问答模式**
    *   **接口**: `POST /api/v1/agent/chat/structured`
    *   **Request Header**: 同上。
    *   **响应 Data**: `RagStructuredResponseDTO` (包含最终 `answer` 答案文本、`citations` Citations 数组对象，以及 `suggestedQuestions` 推荐的后续发散问题)。

---

## 2. 前端设计与集成原则 (Frontend Guidelines)

### 2-1. Agentic ReAct 流式问答设计 (核心规范)

对于基于 `/api/v1/agent/chat/stream` 的 SSE 流接口，前端的职责从单纯的“渲染字符”转变成了“按事件驱动 UI 局部渲染 (Rich UI Rendering)”。

**处理 `Event-Stream` 细则**：

1.  **事件监听路由**:
    ```javascript
    // 伪代码示例：使用 fetch / EventSource / @microsoft/fetch-event-source 等等
    const response = await fetch("/api/v1/agent/chat/stream", {
        method: "POST",
        headers: {
            "Content-Type": "text/plain", // 注意：后端接收普通 string 请求体
            "Authorization": `Bearer ${token}`,
            "X-Session-Id": sessionId
        },
        body: "请帮我查一下2025年的第一季度财报"
    });
    // 解析 SSE ...
    ```

2.  **响应展示优先级 (Action & Tool -> Observation -> Markdown)**
    *   **收到 `event: tool_call` 時**: 该事件意味着 ReAct 框架正陷入思考并决定了调用哪一个 RAG/检索工具。前端可以展示类似于 ChatGPT 那样的一个呼吸灯加载条标签，提示：“🔄 `正在检索企业知识库...`”。
    *   **收到 `event: message` 时**: 这是模型根据 observation 生成出来的总结性回复。前端应当将接收到的 Chunk 增量拼接到消息记录中。应当使用如 `markdown-it` 和 `highlight.js` 实现 Markdown 实时渲染。
    *   **收到 `event: citations` 时**: 这是该问题的引用片段和结构数据集合。前端应当将消息框尾部附加一块**“参考资料卡片”**控件，点击该卡片可以定位到具体的源文件与段落。

### 2-2. Session 会话生命周期与路由规划

如设计文档明确强调：**"没有 Session，Agent 和 Memory 模块都无法正确工作"**。

1.  **全局会话强制机制**
    默认的主页面不允许处于“无会话悬空状态”。只要用户进入 Chat 面板，如果当前没有激活选中的 Session，前端应**自动调用 `POST /api/v1/sessions`** 帮用户申请一个新的 Session，并将该生成的 `sessionId` 塞进全局状态（如 Redux/Pinia/Zustand）。
2.  **后续对话统一挟带**
    此后的一切发起给大模型的交互请求，必须在 Request Header 中挂载 `X-Session-Id: <sessionId>`，否则后端将触发错误。
3.  **UI隔离**
    左边栏做 Session 列表，右边栏做对话主界面。每点击切换一个 Session，必须调用 `PUT /api/v1/sessions/{sessionId}/activate` 告知后端上下文已切换，清空页面上的 Chat 队列，再调用 GET messages 加载该话题过往全部记录。

### 2-3. 与长耗时离线管道设计的配合原则

对于企业文档解析，后端采取了轻量化 Web 端 + 离线分片向量化的方式。

1.  **轮询（Polling）或 SSE 监听**
    在调用上传之后，获取到 `documentId`。页面应该显示该文档进入了 “解析中” 或 “排队中” 的状态，前端开启一个 5 秒左右的定时器轮询调取 `GET /api/v1/documents/{documentId}/status` 直至出现最终完成（Ready/Failed）态。
2.  **错误捕获及展示**
    若是 `status` 为 Failed，允许用户点击展示完整的错误记录日志。

### 2-4. 网络拦截与隔离机制保护

1.  响应业务的 Axios (或 Fetch) 包装器必须实现对 `401 Unauthorized` 和 `403 Forbidden` 原生协议码的精准捕获处理。
2.  若遇到这种底层拦截，前端不能盲目根据 `data.code !== 200` 决定逻辑，必须通过 HTTP statusCode 进行硬路由阻断并强退用户到 Login 页。
