# Spring Security WebFlux 响应式重构记录

> 响应式的传染性（Reactive Virality）指的是：一旦在入口端（Controller）引入了非阻塞的异步流，底层的整个调用链路都会被迫改变编程心智。

## 1. 重构背景
系统在验证 `/api/v1/sessions` 等接口时，虽然 JWT Token 校验成功，但仍然抛出 `SYS10009 (Unauthorized: No authentication found)` 的错误。
根本原因在于：系统底层使用了 Project Reactor（WebFlux）的响应式异步非阻塞架构，而原本的 Spring Security 配置沿用了基于 Servlet API 的传统模型。传统的 `SecurityContextHolder` 是基于 `ThreadLocal` 存储的，在 WebFlux 的非阻塞线程（如Netty的 `boundedElastic` 分发池）切换时，会导致 `SecurityContext` 上下文丢失。

为了彻底解决此问题，我们对系统的安全验证层和控制层进行了**全盘 WebFlux 响应式改造**。

## 2. 完成的主要工作

### (1) 清理 MVC 依赖
修改 `pom.xml`，移除了传统的 `spring-boot-starter-web` 依赖，确保项目只保留纯粹的 WebFlux 运行环境，避免 MVC 与 WebFlux 混合导致配置冲突。

### (2) 重构全局安全配置 `SecurityConfig`
- 移除了传统的 Servlet 相关的 Security 配置。
- 引入 `@EnableWebFluxSecurity` 注解。
- 采用 `ServerHttpSecurity` 构建安全的 `SecurityWebFilterChain`，并规整 API 的放通（`permitAll`）和鉴权规则。
- 将 `.anyRequest()` 替换为适用于 WebFlux 的 `.anyExchange()`。

### (3) 改写核心认证过滤器 `TenantAuthenticationFilter`
- 放弃实现 `OncePerRequestFilter`（基于 ServletRequest）。
- 重新实现 `WebFilter` 接口处理 `ServerWebExchange`。
- 将基于 Redis 的双重令牌验证逻辑包装在 `Mono.fromCallable` 等响应式结构中。
- **关键修复**：将认证成功后的 Token 信息组装为 `Authentication`，并写入原生的 `ReactiveSecurityContextHolder`，保证上下文随着 Reactor 的 Context 链式传递，而不再依赖 `ThreadLocal`。

### (4) 升级工具类 `SecurityUtils`
将 `getCurrentUserId()` 和 `getCurrentTenantId()` 方法的返回值由同步阻塞的 `String` 修改为异步非阻塞的 `Mono<String>`。底层通过获取 `ReactiveSecurityContextHolder.getContext()` 动态读取当前用户的认证信息。

### (5) 重构全线 API 控制器 (Controllers)
将所有需要获取 `UserId` 或 `TenantId` 的方法链路全部采用反应式流式调用（Reactive Chains）串联：
- **`AgentController`**：使用 `.flatMap()` 结合 `Mono.fromCallable()` 处理结构化生成；针对流式响应，采用 `.flatMapMany()` 桥接 `Flux<ServerSentEvent<String>>`。
- **`SessionController`**：全面将获取 `UserId` 的结果与后续对应的增删改查服务使用 `.flatMap()` 和 `.map()` 等流式操作符层层链接。
- **`DocumentController`**：通过 `.flatMap(tenantId -> ...)` 传递动态的租户隔离数据，消除原有的同步代码调用问题。

### (6) 解决编译与环境编码问题
- 清理了由 PowerShell 环境引发的 Java 源码 UTF-8 BOM 头 `\ufeff` 字符导致的编译阻断。
- 确保所有的修改经过 `mvn clean compile` 以及 `mvn test` 编译测试无误。

## 3. 最终效果
全面适配基于 Reactor Context 的响应式上下文传递，彻底消除了由异步线程切换导致的用户身份信息丢失（ThreadLocal Context Drop）问题，充分释放了 WebFlux 的高并发性能优势。