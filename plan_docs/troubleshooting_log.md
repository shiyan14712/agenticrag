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
2. **规范启发**：在后续应对 `JwtException` 或者通过 `ExceptionHandler` 强行捕捉时，考虑加上日志记录 `log.error("JWT处理抛出异常: ", e)` 保留堆栈，防止类似的内部抛错被 HTTP 200 包裹成正常校验被拒而无从下手。