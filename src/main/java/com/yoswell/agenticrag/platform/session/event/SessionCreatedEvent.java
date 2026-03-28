package com.yoswell.agenticrag.platform.session.event;

/**
 * 会话创建事件 (Session Created Event).
 *
 * <p>根据系统设计规范（CLAUDE.md），此事件主要用于解耦生命周期操作。
 * 当全新会话被创建并落库后，系统触发此事件。
 *
 * <p>核心应用场景：
 * <ul>
 *   <li><b>长期记忆注入：</b> 监听器捕获该事件后，可根据 {@code userId} 异步读取 MySQL
 *       {@code user_global_memory} 表中的长期记忆，将其转化为 System Prompt 注入对话初始上下文中。</li>
 * </ul>
 *
 * @param sessionId 新创建的会话ID
 * @param userId    所属用户ID（确保隔离性）
 */
public record SessionCreatedEvent(String sessionId, String userId) {}
