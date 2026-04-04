

# 故障排查与 Bug 修复记录 (Troubleshooting Log)

本文档用于记录项目开发过程中及长线维护中遇到的典型 Bug、排障排查过程、底层的错误原理以及最终解决方案。

## 📝 记录规范 (Template)
以后每次记录，请遵循以下标准的四个结构：
- **日期 (Date)**：发生与修复的时间
- **现象描述 (Symptom)**：暴露出来的错误表象是什么，API报错内容或者界面表现。
- **根因分析 (Root Cause Analysis)**：深入业务代码、开源框架底层的真实原因，展示分析脉络。
- **解决方案 (Resolution)**：具体的修复手段、配置变更代码修改或服务器运维调整。

---

## 🐞 [2026-03-30] JWT 生成失败引发的“假拦截”假象

### 现象描述 (Symptom)
用户请求登录接口 `/api/v1/auth/login` 进行登录，输入了完全正确的账户和密码，且 `SecurityConfig` 配置里已经明确放行白名单了 `/login` 路径。但接口依然返回了如下信息：
```json
{
    "code": "UNAUTHORIZED",
    "message": "令牌错误, 请重新登录",
    "data": null
}
```

### 根因分析 (Root Cause Analysis)
非常经典的“被全局异常处理误导”的问题：
1. **未被拦截而是执行到了末尾**：请求并没有在进入 Controller 前的 `TenantAuthenticationFilter` 卡住，而是成功完成了账密验证，并且进入到了 `UserServiceImpl.login()` 末尾的 `generateToken()` 签发阶段。
2. **秘钥要求不满足**：项目引入的 `io.jsonwebtoken` (JJWT) 底层强制要求 HMAC-SHA256 加密算法的秘钥长度**至少为 256 位（即 32 个字节）**。
3. **抛出异常原因**：原 `application.yaml` 中的 `jwt.secret` (`YWdlbnRpY3JhZw==`) base64解码后只有 `agenticrag`（10 个字节）。所以在 `Jwts.builder().signWith(key)` 步骤直接抛出了 `io.jsonwebtoken.security.WeakKeyException`。
4. **全局异常捕获“误伤”**：`WeakKeyException` 继承于 `io.jsonwebtoken.JwtException`。这个异常往上抛，刚好被 `GlobalExceptionHandler` 捕获到了。全局处理中只要碰到 `JwtException` 就暴力返回“令牌错误”，从而产生了“输入账号密码却提示没权限”的误导性表现。

### 解决方案 (Resolution)
1. **调整 Yaml 配置**：将 `application.yaml` 中原本长度不够的 `secret` 替换为超过 32 字节长度并进行 Base64 编码的绝对强密码：
    ```yaml
    jwt:
      # 旧配置
      # secret: "YWdlbnRpY3JhZw=="
      # 新配置 (还原后为: this_is_a_very_long_secret_key_that_is_at_least_32_bytes_for_agenticrag)
      secret: "dGhpc19pc19hX3ZlcnlfbG9uZ19zZWNyZXRfa2V5X3RoYXRfaXNfYXRfbGVhc3RfMzJfYnl0ZXNfZm9yX2FnZW50aWNyYWc="
    ```
2. **规范启发**：在后续应对 `JwtException` 或者通过 `ExceptionHandler` 强行捕捉时，考虑加上日志记录 `log.error("JWT 处理抛出异常：", e)` 保留堆栈，防止类似的内部抛错被 HTTP 200 包裹成正常校验被拒而无从下手。

---

## 🐞 [2026-03-31] LangChain4j 序列化故障引发的 OpenAI `list index out of range` 与注解缺失异常

### 现象描述 (Symptom)
在重构分层记忆组件并调用 RAG 智能体对话接口（`AgentController`）后，系统连续发生两次拦截报错：
1. **序列化丢失导致 OpenAI 拒绝请求**：调用大模型生成回复时，底层的 OpenAI SDK 抛出异常 `OpenAiHttpException: {"error":{"message":"list index out of range","type":"BadRequestError","param":null,"code":400}}`。
2. **AiService 参数配置异常**：修复序列化问题后，系统在代理接口映射时立刻抛出 `dev.langchain4j.exception.IllegalConfigurationException: Parameter 'userMessage' of method 'chat' should be annotated with @V or @UserMessage or @UserName or @MemoryId`。

### 根因分析 (Root Cause Analysis)
这两个故障都源于框架底层的严格规范与三方组件数据结构的不兼容：

1. **Redis 默认的 Jackson 序列化与 LangChain4j 模型的冲突**：
    - LangChain4j 的 `ChatMessage` 体系（如 `UserMessage`、`AiMessage` 等对象）并未采用传统的 Java Bean 规范（缺乏无参构造器，也没有以 `get/set` 开头的属性访问器，例如它的取值方法直接叫 `text()`）。
    - Spring Boot 中采用 `GenericJacksonJsonRedisSerializer` 或标准 `ObjectMapper` 处理这类非标对象时，会因为无法反射找到属性而丢失字段，或者在反序列化时被强转成无具体类型的 `LinkedHashMap`。当代码中试图使用 `objectMapper.readValue(json, ChatMessage.class)` 接口来强行反序列化数据时，直接失效抛错，最终得到一个**空集合** `[]`。
    - **雪崩效应**：这个空的对话记忆列表被强行塞入了 OpenAI 接口中作为上下文，但 OpenAI 的 Chat Completions API 明确规定 `messages` 数组不能为空，进而引发 HTTP 400 Bad Request 和 `list index out of range` 的错误。
2. **@AiService 反射与参数绑定严格校验**：
    - LangChain4j 框架利用动态代理构建 `@AiService` 实现时，必须**精准识别每个参数的作用**。
    - 当代理接口（如 `EnterpriseAgent`）方法中存在多个参数（如 `(@MemoryId String sessionId, String userMessage)`）时，如果不显式声明 `@UserMessage`，在开启了 Java 编译参数丢弃或其他反射特性后，代理处理器就无法安全推断剩下的参数谁才是 Prompt，从而出于安全防护阻断了应用运行。

### 解决方案 (Resolution)
1. **替换原生序列化工具，跳过 Spring 原生 Jackson**：
    - 修改 `HierarchicalChatMemoryStore.java` 中 L1 Redis 缓存的存取逻辑。
    - **写入时**：利用 LangChain4j 官方支持的工具箱将其转换成字符串 `dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(recentMessages)`，再使用 RedisTemplate 存入字符串。
    - **读取时**：利用 `dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson(jsonString)` 直接将字符串安全解析回准确的 `List<ChatMessage>` 集合，彻底打破 Jackson 对于底层组件序列化的兼容性限制。
2. **补全声明式的语义注解**：
    - 为所有使用 `@AiService` 的接口统统补充明确的参数注解修饰。确保其符合 Langchain4j Service 的强校验要求。
    - 包括 `EnterpriseAgent`、`IntentRouterAgent` 和 `RagStructuredAgent`，统一将类似的传参 `String userMessage` 明确加上 `@UserMessage` 注解：`(@MemoryId String sessionId, @UserMessage String userMessage)`。

---

## 🐞 [2026-03-31] RAG 检索时 Doubao Embedding 反序列化报错 ClassCastException

### 现象描述 (Symptom)
在重构了 ReAct 模式后，大模型自主调用 `RagTool` 执行知识库检索。调用链路执行到向量转化步骤时，后台爆出严重错误，导致检索动作阻断：
```text
java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.Double (java.lang.Integer and java.lang.Double are in module java.base of loader 'bootstrap')
	at com.yoswell.agenticrag.core.agent.llm.DoubaoMultimodalEmbeddingModel.embedAll(DoubaoMultimodalEmbeddingModel.java:68)
```
外部大模型接口会获得 `Search failed` 的工具反馈。

### 根因分析 (Root Cause Analysis)
该错误发生在自定义外部 API 适配类 `DoubaoMultimodalEmbeddingModel` 的 `RestTempate` 响应反序列化解析阶段（使用 Jackson 等内置器）。
1. **数据混合类型**：豆包服务端返回的 Embedding（向量）数组是一个浮点数数组，但是在 JSON 字符串传输表现中，如果有某个维度的计算结果恰好是整型或 `0`，JSON 中会直接体现为没有小数点的形式。
2. **反序列化推断偏差**：Java 中使用泛型擦除的 `Map.class`（或者 `List`）去接盘未知 JSON 时，底层的 Jackson / FastJson Parser 会对集合中的每一项逐一类型推断。如果某个数值是类似 `0.0123` 则解析成了 `java.lang.Double`；如果某个数值是 `0` 则被解析为了 `java.lang.Integer`。
3. **强转型崩溃**：原代码写法 `List<Double> vectorDouble = (List<Double>) data.get("embedding")` 属于不安全的强行擦除转型。当随后遍历列表调用隐式的内部拆箱位时，遇到了数组当中的 `Integer` 对象，导致在尝试作为 `Double` 处理时抛出 `ClassCastException`。

### 解决方案 (Resolution)
1. **拓宽泛型至通用父类 `Number`**：
    将强制接收的泛型从 `List<Double>` 变更为更宽泛的 `List<Number>`。`java.lang.Number` 是 `Double`、`Integer`、`Float` 等数字类型的共同父类，可以完美兼容从 JSON 中动态推断出的所有数值对象。
2. **安全提取浮点数**：利用多态在拆包时统一执行 `Number.floatValue()` 处理：
    ```java
    @SuppressWarnings("unchecked")
    List<Number> vectorNumbers = (List<Number>) data.get("embedding");

    float[] vector = new float[vectorNumbers.size()];
    for (int i = 0; i < vectorNumbers.size(); i++) {
        // 安全提取浮点数，不论此元素当初是 Integer 还是 Double 被反序列化而来
        vector[i] = vectorNumbers.get(i).floatValue(); 
    }
    ```
通过这一改动，不论底层组件抛出的是整数型还是小数型，都能安全一致地转换为 LangChain4j Embedding 需要的 `float[]` 数组。

---

## 🐞 [2026-03-31] OpenAI API 报错 "System message must be at the beginning" 

### 现象描述 (Symptom)
在调用结构化对话接口 `/api/v1/agent/chat/structured` 时，底层 OpenAI API 返回以下错误：
```text
dev.ai4j.openai4j.OpenAiHttpException: {"error":{"message":"System message must be at the beginning.","type":"BadRequestError","param":null,"code":400}}
	at dev.ai4j.openai4j.Utils.toException(Utils.java:8)
	at com.yoswell.agenticrag.core.agent.controller.AgentController.lambda$2(AgentController.java:85)
```
该错误导致所有依赖 LangChain4j + OpenAI 协议的大模型调用全部失败，智能体无法生成任何响应。

### 根因分析 (Root Cause Analysis)
这是一个典型的**消息顺序违规**问题，源于分层记忆系统中 SystemMessage 的位置管理不当：

1. **OpenAI API 的严格要求**：OpenAI Chat Completions API 明确规定，所有 `SystemMessage` 必须出现在 `messages` 数组的**最开头**，不允许在 User/Ai Message 中间或末尾出现。

2. **LangChain4j 记忆组装逻辑缺陷**：
   - 自定义的 `HierarchicalChatMemoryStore.getMessages()` 方法按以下顺序组装消息：
     1. 注入用户偏好（SystemMessage）✅ 位置正确
     2. 注入 L3 摘要（SystemMessage）✅ 位置正确  
     3. 注入 L2 摘要（SystemMessage）✅ 位置正确
     4. **从 Redis 读取 L1 历史消息** ❌ 问题源头
   - 如果用户在历史对话中曾经触发过某些特殊逻辑（如工具调用、系统提示等），Redis 中存储的 L1 消息列表里可能包含 SystemMessage。
   - 这些历史 SystemMessage 会被直接 `addAll()` 到消息列表的**中间位置**，违反 OpenAI 规范。

3. **雪崩效应**：当这个不合规的消息列表传递给 LangChain4j → OpenAI SDK 时，API 校验失败并立即抛出 `BadRequestError`，导致整个对话链路中断。

4. **为什么之前没发现**：
   - 在简单对话场景中，L1 历史消息通常只包含 UserMessage 和 AiMessage，不会混入 SystemMessage。
   - 但当智能体调用 Tool、触发 ReAct 模式、或有其他高级特性时，LangChain4j 可能在对话过程中插入 SystemMessage，导致问题暴露。

### 解决方案 (Resolution)
修改 `HierarchicalChatMemoryStore.getMessages()` 方法，采用**分类收集 + 合并策略**确保消息顺序合规：

1. **创建两个独立容器**：
   ```java
   ArrayList<ChatMessage> systemMessages = new ArrayList<>();
   ArrayList<ChatMessage> otherMessages = new ArrayList<>();
   ```

2. **分类收集消息**：
   - 将所有主动注入的 SystemMessage（用户偏好、L2/L3 摘要）放入 `systemMessages`
   - 从 Redis 读取 L1 历史消息后，遍历检查每条消息的类型：
     ```java
     for (ChatMessage msg : l1Messages) {
         if (msg instanceof SystemMessage) {
             systemMessages.add(msg);  // SystemMessage 统一放前面
         } else {
             otherMessages.add(msg);   // 其他消息放后面
         }
     }
     ```

3. **合并返回**：
   ```java
   systemMessages.addAll(otherMessages);
   return systemMessages;
   ```

4. **核心代码变更**（`HierarchicalChatMemoryStore.java` 第 168-200 行）：
   ```java
   @Override
   public List<ChatMessage> getMessages(Object memoryId) {
       String sessionId = memoryId.toString();
       log.info("Retrieving memory for session: {}", sessionId);

       // 分离 SystemMessage 和其他消息，确保 SystemMessage 始终在开头
       ArrayList<ChatMessage> systemMessages = new ArrayList<>();
       ArrayList<ChatMessage> otherMessages = new ArrayList<>();
       
       // 收集所有 SystemMessage（用户偏好、摘要等）
       injectUserPreferences(sessionId, systemMessages);
       injectSummary(sessionId, "session:memory:l3:", "Long-range session summary", systemMessages);
       injectSummary(sessionId, "session:memory:l2:", "Medium-range session summary", systemMessages);

       // 从 Redis 获取 L1 原始消息
       Object l1Data = redisTemplate.opsForValue().get(MemoryStoreConstants.REDIS_PREFIX_L1 + sessionId);
       if (l1Data != null) {
           List<ChatMessage> l1Messages = deserializeChatMessages(l1Data);
           // 将 L1 消息按类型分离：SystemMessage 放到前面，其他消息放到后面
           for (ChatMessage msg : l1Messages) {
               if (msg instanceof SystemMessage) {
                   systemMessages.add(msg);
               } else {
                   otherMessages.add(msg);
               }
           }
       }

       // 合并：所有 SystemMessage 在前，其他消息在后
       systemMessages.addAll(otherMessages);
       return systemMessages;
   }
   ```

### 经验总结与最佳实践
1. **OpenAI 消息顺序铁律**：
   - SystemMessage → UserMessage → AiMessage → UserMessage → AiMessage ...
   - 绝不允许 SystemMessage 出现在非开头位置

2. **防御性编程建议**：
   - 在返回消息列表前，可以增加一个校验方法，扫描是否有 SystemMessage 出现在错误位置：
     ```java
     private void validateMessageOrder(List<ChatMessage> messages) {
         boolean foundNonSystem = false;
         for (ChatMessage msg : messages) {
             if (!(msg instanceof SystemMessage) && foundNonSystem == false) {
                 foundNonSystem = true;
             } else if (msg instanceof SystemMessage && foundNonSystem) {
                 throw new IllegalStateException("SystemMessage must be at the beginning");
             }
         }
     }
     ```

3. **通用适配原则**：
   - 当集成第三方 API（尤其是闭源商业 API）时，必须严格遵守其输入规范，即使某些场景下"看似可以工作"
   - 对于消息顺序、字段必填性等强约束，要在内部实现中进行防御性校验，提前发现问题而非等到 API 调用失败

4. **与之前问题的联动**：
   - 这是继序列化问题、Embedding 类型转换问题之后，第三个由于 LangChain4j + OpenAI 生态严格规范引发的底层兼容性问题
   - 再次验证了：**框架的便利性背后隐藏着严格的契约要求**，开发者必须深入理解这些隐式约定

---

## 🐞 [2026-04-01] `/api/v1/agent/chat/stream` 流式响应完成后触发 AccessDenied

### 现象描述 (Symptom)
调用流式对话接口 `/api/v1/agent/chat/stream` 时，前端可以先收到部分流式内容，后端也能看到会话落库与流结束日志；但请求尾部会抛出权限异常：
```text
org.springframework.security.authorization.AuthorizationDeniedException: Access Denied
```
外在表现为接口最终以鉴权失败收尾，影响流式会话稳定性。

### 根因分析 (Root Cause Analysis)
该问题由 Servlet 异步分派阶段未重新执行 JWT 认证过滤导致：

1. **流式接口采用 `SseEmitter`**：在 WebMVC 中，`SseEmitter` 会触发 Servlet Async Dispatch（异步再次分派）。
2. **过滤器默认行为导致漏鉴权**：`TenantAuthenticationFilter` 继承 `OncePerRequestFilter`，若不显式覆盖，异步/错误分派阶段默认可能跳过当前过滤器。
3. **安全上下文在后续分派缺失**：异步分派阶段未重新完成 JWT 解析与 `SecurityContext` 建立，`AuthorizationFilter` 在后续鉴权判断时拿不到有效认证信息，最终抛出 `AccessDenied`。

### 解决方案 (Resolution)
**修复认证过滤器分派策略**：在 `TenantAuthenticationFilter` 中显式覆盖以下方法，确保异步与错误分派仍执行 JWT 认证链路：
   ```java
   @Override
   protected boolean shouldNotFilterAsyncDispatch() {
       return false;
   }

   @Override
   protected boolean shouldNotFilterErrorDispatch() {
       return false;
   }
   ```

---

## 🐞 [2026-04-01] 二次鉴权缺失导致会话越权与异常职责边界错位

### 现象描述 (Symptom)
在安全审计中发现两类高风险问题：
1. **会话切换存在越权窗口**：请求 `PUT /api/v1/sessions/{sessionId}/activate` 时，若传入他人 `sessionId`，旧实现存在进入激活流程的可能。
2. **标题查询存在水平越权风险**：请求 `GET /api/v1/agent/title/{sessionId}` 时，旧实现仅按 `sessionId` 查标题，缺少会话归属校验。

同时发现两类设计原则偏离：
- 租户提取存在 `default` 兜底，不符合零信任 fail-closed 原则。
- 全局异常兜底过宽，安全异常存在被业务异常出口吞掉的风险。

### 根因分析 (Root Cause Analysis)
1. **二次鉴权分支空实现**：`SessionContextSwitcher.activateSession()` 中虽有 `userId` 与会话归属比对，但不匹配分支未抛异常，导致控制流可继续向下执行。
2. **对象级权限校验缺失**：标题查询链路中，Controller 未调用 `verifySessionAccess`，Service 仅按 `session_id` 查询，缺少 `user_id` 过滤条件，形成典型 IDOR（Insecure Direct Object Reference）入口。
3. **租户上下文宽松兜底**：`SecurityUtils.getCurrentTenantId()` 在认证缺失时回落到 `default`，会把“应拒绝”场景误降级为“可继续执行”场景，弱化租户边界。
4. **安全异常边界不够明确**：若控制器/业务层抛出认证类异常，被 `GlobalExceptionHandler` 通用 `Exception` 处理器接管时，可能返回业务风格错误体，不利于 401/403 语义一致性。

### 解决方案 (Resolution)
1. **封堵会话切换越权**：在 `SessionContextSwitcher.activateSession()` 中，对“会话不存在”与“归属不匹配”统一抛出 `SESSION_NOT_FOUND` 业务异常，立即中断流程。
2. **补齐标题查询对象级鉴权**：
    - Controller 侧在读取标题前先执行 `sessionService.verifySessionAccess(sessionId, userId)`。
    - Service 侧将标题查询条件升级为 `session_id + user_id` 双条件，防止绕过 Controller 的直接调用。
3. **收紧租户上下文策略**：`SecurityUtils.getCurrentTenantId()` 改为 fail-closed，认证信息缺失或租户为空时直接抛 `AuthenticationCredentialsNotFoundException`，彻底移除 `default` 兜底。
4. **明确鉴权异常出口**：在 `GlobalExceptionHandler` 中新增 `AuthenticationException` 与 `AccessDeniedException` 专属处理，分别返回 HTTP 401/403 与统一 `ApiResponse` 结构，避免安全异常被通用业务出口吞掉。
5. **验证结果**：修复后已通过 `mvnw -DskipTests compile` 编译验证。

---

## 🐞 [2026-04-04] RagRetrievalContextHolder 在 ReAct 异步线程模型下丢失会话上下文，导致 citations 静默丢失

### 现象描述 (Symptom)
在 `/api/v1/agent/chat/stream` 的 ReAct 对话过程中，前端能够正常收到 `thinking / tool_start / tool_result / message` 等 SSE 事件，工具看起来也执行成功；但在流结束阶段，`citations` 经常为空数组，表现为：
1. 大模型确实调用了 `search_enterprise_knowledge`，但最终回答没有引用卡片。
2. `onCompleteResponse` 中 `ragRetrievalContextHolder.consume(sessionId)` 返回 `Optional.empty()`。
3. 多轮工具调用场景下，引用信息不是部分丢失就是完全丢失。

### 根因分析 (Root Cause Analysis)
该问题本质是 **ThreadLocal 传播假设与真实线程拓扑不一致**：

1. **旧实现依赖 `InheritableThreadLocal`**：
    - `dispatchDynamicStream()` 中通过 `bindSession(sessionId)` 将会话写入 `InheritableThreadLocal`。
    - `publish(result)` 再通过 `activeSessionId.get()` 读取会话并聚合结果。

2. **ReAct 回调与 Tool 执行发生在线程池线程**：
    - `Thread.startVirtualThread(...)` 只负责启动流。
    - LangChain4j 的 `TokenStream.start()` 后续回调（含 Tool 执行）可能运行在 IO/Executor 线程，而不是虚拟线程本身或其子线程。
    - `InheritableThreadLocal` 无法跨线程池传递到这些回调线程。

3. **直接后果是静默丢弃**：
    - `publish(result)` 里读取到 `sessionId == null`，旧逻辑直接 `return`。
    - 结果未进入 `sessionResults`，最终 `consume(sessionId)` 取不到数据，`citations` 为空。

4. **为什么 SSE 主流看起来正常**：
    - `SseEmitter` 支持跨线程发送，`message`/`tool_*` 推送本身不依赖 `ThreadLocal`。
    - 所以“文本流正常但引用丢失”成为典型误导现象。

### 解决方案 (Resolution)
本次修复将上下文传播从“隐式 ThreadLocal”改为“显式线程-会话映射 + 生命周期清理”：

1. **重构 `RagRetrievalContextHolder` 上下文模型**：
    - 删除 `InheritableThreadLocal`。
    - 新增 `threadToSession: Map<Long, String>`，按 `threadId -> sessionId` 记录当前线程绑定。
    - 新增 `sessionToThreads: Map<String, Set<Long>>`，用于回答结束时反向批量清理。

2. **新增线程绑定 API**：
    - `bindCurrentThread(sessionId)`：入口线程作用域绑定。
    - `registerCurrentThread(sessionId)`：在当前回调线程注册会话。
    - `unregisterCurrentThread()`：解除当前线程绑定。
    - `clearSessionBindings(sessionId)`：在完成/异常时清理该会话所有线程绑定，避免线程池复用串会话。

3. **编排器侧接入显式注册与清理**（`ChatOrchestrator`）：
    - 流启动时先 `clearSessionResult(sessionId)` 并 `bindCurrentThread(sessionId)`。
    - 在 `onPartialThinking / beforeToolExecution / onToolExecuted / onPartialResponse / onCompleteResponse / onError` 回调入口执行 `registerCurrentThread(sessionId)`。
    - 在 `onCompleteResponse / onError / catch` 中执行 `clearSessionBindings(sessionId)`，并在异常路径清理 `clearSessionResult(sessionId)`。

4. **可观测性增强**：
    - `publish()` 在未绑定会话时改为 `warn` 日志，不再无声失败，便于快速定位线程上下文问题。

5. **验证结果**：
    - 已执行 `mvnw.cmd -DskipTests clean compile`，构建通过（BUILD SUCCESS）。
    - 在多次 Tool 调用场景下，`sessionResults` 可正确按 `chunkId` 合并并在 `onCompleteResponse` 输出 citations。

---
