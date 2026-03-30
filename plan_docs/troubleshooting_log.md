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

## 🐞 [2026-03-31] Redis 序列化导致的 ClassCastException

### 现象描述 (Symptom)
用户调用流式聊天接口 `/api/v1/agent/chat/stream` 时，后端日志抛出 `ClassCastException` 异常：
```
java.lang.ClassCastException: class java.lang.String cannot be cast to class dev.langchain4j.data.message.ChatMessage
```
错误发生在 langchain4j 的 `MessageWindowChatMemory.findSystemMessage()` 方法中，导致整个对话流程中断，前端无法收到响应。

### 根因分析 (Root Cause Analysis)
这是一个典型的**Redis 序列化/反序列化不一致**问题：

1. **数据流转路径**：
   - `HierarchicalChatMemoryStore.updateMessages()` 将 `List<ChatMessage>` 存入 Redis L1 缓存
   - RedisTemplate 使用默认的 JDK 序列化器或 JSON 序列化器将其序列化为字符串
   - 下次读取时，`getMessages()` 从 Redis 获取到的是 String 类型数据
   
2. **强制转换失败点**：
   - 原代码在第 72 行直接进行 unchecked 强转：`(List<ChatMessage>) redisTemplate.opsForValue().get(...)`
   - Java 泛型擦除使得运行时无法检查类型，实际返回的是 `String`
   - 当 langchain4j 遍历这些消息时，尝试将 `String` 当作 `ChatMessage` 处理，抛出异常

3. **框架层触发**：
   - langchain4j 的 `DefaultAiServices.classify()` 调用 `MessageWindowChatMemory.add()`
   - `add()` 方法内部调用 `findSystemMessage()` 遍历内存中的消息
   - Stream API 的 `forEachWithCancel` 遇到类型不匹配的数据直接崩溃

4. **根本原因**：
   - Redis 存储的 Object 被序列化为什么格式没有统一约定
   - 读取端假设一定是 `List<ChatMessage>`，缺少类型检查和适配逻辑

### 解决方案 (Resolution)
修改 [`HierarchicalChatMemoryStore.java`](file://D:\Projects\Java\agenticrag\src\main\java\com\yoswell\agenticrag\core\memory\store\HierarchicalChatMemoryStore.java) 实现智能反序列化：

1. **引入 ObjectMapper 依赖**（第 16-17 行、第 42 行、第 60 行）：
   ```java
   import com.fasterxml.jackson.core.JsonProcessingException;
   import com.fasterxml.jackson.databind.ObjectMapper;
   
   // 构造函数中初始化
   this.objectMapper = new ObjectMapper();
   ```

2. **重构 `getMessages()` 方法**（第 77-83 行）：
   - 移除不安全的强制类型转换
   - 先获取原始 Object 数据
   - 委托给专门的反序列化方法处理

3. **新增 `deserializeChatMessages()` 方法**（第 233-254 行）：
   ```java
   @SuppressWarnings("unchecked")
   private List<ChatMessage> deserializeChatMessages(Object data) {
       if (data instanceof List<?> list) {
           // 如果已经是 ChatMessage 列表，直接返回
           if (!list.isEmpty() && list.get(0) instanceof ChatMessage) {
               return (List<ChatMessage>) list;
           }
           // 如果是其他类型的列表，尝试转换
           return list.stream()
                   .map(this::convertToChatMessage)
                   .filter(msg -> msg != null)
                   .collect(Collectors.toList());
       } else if (data instanceof String jsonString) {
           // 如果是 JSON 字符串，尝试反序列化
           try {
               return objectMapper.readValue(jsonString, 
                   objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
           } catch (JsonProcessingException e) {
               log.warn("Failed to deserialize ChatMessage list from JSON: {}", jsonString, e);
               return new ArrayList<>();
           }
       } else {
           log.warn("Unexpected data type for ChatMessage list: {}", data.getClass().getName());
           return new ArrayList<>();
       }
   }
   ```

4. **新增 `convertToChatMessage()` 辅助方法**（第 256-279 行）：
   - 处理 `String` 类型 → 包装为 `UserMessage.from(text)`
   - 处理 `Map` 类型 → 通过 Jackson 转换为 `ChatMessage`
   - 处理已知的 `ChatMessage` 子类 → 直接返回
   - 提供完善的错误处理和警告日志

5. **添加必要的导入**（第 5 行、第 15-16 行）：
   ```java
   import java.util.Map;
   import com.fasterxml.jackson.core.JsonProcessingException;
   import com.fasterxml.jackson.databind.ObjectMapper;
   ```

**修复效果**：
- ✅ 支持多种数据格式的自动识别和转换
- ✅ 避免 unchecked 强制转换导致的运行时异常
- ✅ 增强系统健壮性，即使 Redis 数据格式变化也能优雅降级
- ✅ 提供详细的日志输出，便于后续问题排查