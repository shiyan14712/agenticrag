package com.yoswell.agenticrag.core.agent.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.langchain4j.data.message.SystemMessage;

/**
 * 系统提示词组装器
 *
 * <p>
 * 统一管理所有 system prompt 片段的注册与组装，确保发送给模型的消息列表中
 * 始终只有一条 {@link SystemMessage}，兼容 vLLM 等只接受单条 system 消息的后端。
 * </p>
 *
 * <h3>设计模式：Registry + Builder</h3>
 * <ul>
 * <li><strong>Registry</strong>：静态片段在应用启动时通过 {@link #registerStatic} 注册，
 *     运行期不可变，保证线程安全（写入仅发生在 {@code @PostConstruct} 阶段）</li>
 * <li><strong>Builder</strong>：每次请求时由调用方传入动态片段，
 *     {@link #assemble} 将静态 + 动态片段合并为单条 {@link SystemMessage}</li>
 * </ul>
 *
 * <h3>片段优先级（从高到低）：</h3>
 * <ol>
 * <li>静态片段（角色定义、工具使用规范等，启动时注册，排在最前）</li>
 * <li>动态片段（用户偏好、L3/L2 摘要等，每次请求时传入，排在其后）</li>
 * </ol>
 *
 * <h3>使用方式：</h3>
 * <pre>{@code
 * // 1. 启动时注册静态片段（在 @PostConstruct 中调用）
 * assembler.registerStatic("You are an enterprise AI assistant.");
 *
 * // 2. 请求时组装（由 HierarchicalChatMemoryStore 调用）
 * Optional<SystemMessage> systemMsg = assembler.assemble(dynamicSegments);
 *
 * // 3. 过滤合成消息（在 updateMessages 中调用）
 * boolean synthetic = assembler.isSynthetic(someSystemMessage);
 * }</pre>
 *
 * @see com.yoswell.agenticrag.core.memory.store.HierarchicalChatMemoryStore
 * @see com.yoswell.agenticrag.core.agent.service.orchestrator.ChatOrchestrator
 */
@Component
public class SystemPromptAssembler {

    /**
     * 合成 SystemMessage 的标记头。
     * <p>
     * 所有由本组装器生成的 SystemMessage 均以此字符串开头，
     * 用于在 {@code updateMessages} 阶段识别并过滤合成消息，避免将其持久化到 L1 缓存后重复注入。
     * </p>
     */
    public static final String SYNTHETIC_MARKER = "AgenticRag Internal System Context";

    /**
     * 启动时注册的静态片段，有序。
     * <p>仅在 {@code @PostConstruct} 阶段写入，之后只读，无需同步。</p>
     */
    private final List<String> staticSegments = new ArrayList<>();

    /**
     * 注册一个静态系统提示词片段。
     *
     * <p>
     * 应在 Spring 容器启动完成后（{@code @PostConstruct}）调用，运行期不应再修改。
     * 多次调用按注册顺序排列，静态片段始终排在动态片段之前。
     * </p>
     *
     * @param content 片段内容，空值或空白字符串将被忽略
     */
    public void registerStatic(String content) {
        if (content != null && !content.isBlank()) {
            staticSegments.add(content.trim());
        }
    }

    /**
     * 将静态片段与动态片段合并，组装为单条 {@link SystemMessage}。
     *
     * <p>
     * 处理流程：静态片段 + 动态片段 → 去重（保留首次出现顺序）→ 过滤空值
     * → 添加 {@link #SYNTHETIC_MARKER} 标记头 → 双换行拼接。
     * </p>
     *
     * @param dynamicSegments 本次请求的动态片段（用户偏好、L2/L3 摘要等），可为 {@code null} 或空列表
     * @return 组装后的 SystemMessage；若所有片段均为空则返回 {@link Optional#empty()}
     */
    public Optional<SystemMessage> assemble(List<String> dynamicSegments) {
        // LinkedHashSet 保证去重的同时维持插入顺序：静态片段在前，动态片段在后
        LinkedHashSet<String> all = new LinkedHashSet<>();

        staticSegments.stream()
                .filter(s -> s != null && !s.isBlank())
                .forEach(all::add);

        if (dynamicSegments != null) {
            dynamicSegments.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .forEach(all::add);
        }

        if (all.isEmpty()) {
            return Optional.empty();
        }

        String combined = SYNTHETIC_MARKER + "\n\n" + String.join("\n\n", all);
        return Optional.of(SystemMessage.from(combined));
    }

    /**
     * 判断一条 {@link SystemMessage} 是否由本组装器生成。
     *
     * <p>用于 {@code updateMessages} 阶段过滤合成消息，避免将其持久化到 L1 缓存。</p>
     *
     * @param message 待检查的系统消息
     * @return {@code true} 表示该消息由本组装器生成，应被过滤
     */
    public boolean isSynthetic(SystemMessage message) {
        String text = message.text();
        return text != null && text.startsWith(SYNTHETIC_MARKER);
    }
}
