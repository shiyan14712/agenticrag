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
