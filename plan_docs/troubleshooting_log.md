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
