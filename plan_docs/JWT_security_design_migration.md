# Spring Security 迁移记录（WebFlux -> WebMVC）

## 1. 迁移背景
项目早期为适配响应式链路采用了 WebFlux + Reactor Context 的安全实现，但在实际业务中数据库与对象存储仍以阻塞 I/O 为主（MyBatis/JDBC、MinIO、ES 客户端）。

在该前提下继续维护 `Mono/Flux` 链会带来以下问题：
- 复杂的 `flatMap` 嵌套和线程切换开销。
- JWT 上下文在跨线程场景下的可读性与维护成本较高。
- Agent 编排流程（查库 -> 调模型 -> 落库）难以保持线性可读。

因此本项目将安全链路和控制器调用模型统一回迁到 **WebMVC + Virtual Threads**。

## 2. 已完成改造

### (1) 安全配置回迁到 Servlet 体系
- `SecurityConfig` 使用 `@EnableWebSecurity` + `HttpSecurity`。
- 过滤链由 `SecurityWebFilterChain` 回迁到 `SecurityFilterChain`。
- 开启 `SessionCreationPolicy.STATELESS`，明确 JWT 无状态会话策略。
- 401/403 通过 `AuthenticationEntryPoint` 与 `AccessDeniedHandler` 统一输出 `ApiResponse<Void>`。

### (2) 认证过滤器回迁
- `TenantAuthenticationFilter` 回迁为 `OncePerRequestFilter`。
- 使用 `HttpServletRequest/HttpServletResponse` 处理请求。
- 认证成功后将 `Authentication` 写入 `SecurityContextHolder`。

### (3) 安全工具类回迁
- `SecurityUtils.getCurrentUserId()` 与 `getCurrentTenantId()` 回归同步 `String` 返回。
- 移除 `ReactiveSecurityContextHolder` 依赖，直接读取 `SecurityContextHolder`。

### (4) 控制器/服务 I/O 风格统一
- 主要 Controller 与相关 Service 移除 `Mono/Flux` 与 `subscribeOn(boundedElastic)` 包装。
- 流式输出改用 `SseEmitter` + 虚拟线程。

### (5) JWT 行为修正
- 修复登出逻辑中对 `Authorization: Bearer ...` 的解析，确保 Access Token 在 Redis 中正确撤销。

## 3. 当前结论
当前安全链路已经与 WebMVC 主干对齐，JWT 校验、权限拦截和业务上下文读取均在 Servlet 线程模型下工作。

后续需要持续关注虚拟线程子任务中的 `SecurityContext` 传播，避免跨线程执行时上下文丢失。