package com.yoswell.agenticrag.platform.session.event;

/**
 * 会话切换事件 (Session Switched Event).
 *
 * <p>根据系统设计规范（CLAUDE.md），此事件是实现“会话切换不可阻塞”铁律的关键设计。
 * 会话切换本身应在 200ms 内返回，所有耗时的清理与压缩操作必须解耦并异步执行。
 *
 * <p>核心应用场景：
 * <ul>
 *   <li><b>异地持久化与清理：</b> 触发 {@code SessionContextSwitcher} 对 {@code oldSessionId} 的旧会话收尾持久化。</li>
 *   <li><b>L2/L3 分级记忆压缩：</b> 异步调度轻量级 LLM 对旧会话执行摘要提炼（L2），或者对超长会话进行重度压缩提取核心实体（L3）。</li>
 * </ul>
 *
 * @param oldSessionId 切换前所处旧会话ID（用于触发异步压缩和落库）
 * @param newSessionId 切换后的目标会话ID
 * @param userId       所属用户ID（供安全校验拦截器使用）
 */
public record SessionSwitchedEvent(String oldSessionId, String newSessionId, String userId) {}
