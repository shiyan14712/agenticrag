package com.yoswell.agenticrag.web.security.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.yoswell.agenticrag.web.security.model.TenantUser;

/**
 * 基于 TransmittableThreadLocal 的租户上下文持有器
 *
 * <p>
 * 专为虚拟线程 + 线程池场景设计标准 {@code ThreadLocal} 在以下场景会悄无声息地丢失上下文：
 * </p>
 * <ul>
 * <li>Langchain4j 的 TokenStream 回调在不同虚拟线程上触发</li>
 * <li>{@code Thread.startVirtualThread()} 启动的子任务</li>
 * <li>Tomcat 虚拟线程 Executor 复用载体导致串讲</li>
 * </ul>
 *
 * <p>
 * {@link TransmittableThreadLocal} 通过"装饰器模式"在任务提交瞬间自动捕获当前副本，
 * 并在执行线程内恢复，任务结束后自动清理，完整生命周期管理
 * 配合 {@code TtlRunnable.get(runnable)} 使用即可开启传播，无需 Java Agent
 * </p>
 *
 * <p>
 * 使用约定：
 * </p>
 * <ol>
 * <li>在 {@code TenantAuthenticationFilter} 写入，请求结束后由 {@code afterCompletion} 清理</li>
 * <li>子线程（虚拟线程/线程池）通过 {@code TtlRunnable} 包装后可透明读取</li>
 * <li>不要在子线程内修改值，所有写操作在请求主线程完成</li>
 * </ol>
 *
 * @see com.alibaba.ttl.TransmittableThreadLocal
 * @see com.alibaba.ttl.TtlRunnable
 */
public final class TenantContextHolder {

    private TenantContextHolder() {
        // utility class
    }

    private static final TransmittableThreadLocal<TenantUser> CONTEXT =
            new TransmittableThreadLocal<>();

    /**
     * 写入当前请求的租户用户身份
     * <p>应在 Filter/Interceptor 入口调用，确保主线程先行绑定</p>
     *
     * @param tenantUser 已通过 JWT 验证的租户用户，不可为 null
     */
    public static void set(TenantUser tenantUser) {
        CONTEXT.set(tenantUser);
    }

    /**
     * 读取当前线程（或通过 TTL 传播到的子线程）的租户用户身份
     *
     * @return 租户用户，若未设置则为 {@code null}
     */
    public static TenantUser get() {
        return CONTEXT.get();
    }

    /**
     * 清除当前线程的上下文副本
     * <p>必须在请求结束（{@code afterCompletion} 或 {@code doFilter} finally 块）调用，防止内存泄漏</p>
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
