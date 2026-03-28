package com.yoswell.agenticrag.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/agent/session")
public class SessionController {

    /**
     * 拉取目标 Session 在 Redis 中（或者 MySQL中经过压缩的）历史对话上下文
     * 作用于：前台刷新浏览器或在左侧列表点击某个历史会话时重绘聊天气泡
     */
    @GetMapping("/{sessionId}/history")
    public Mono<List<Map<String, String>>> getSessionHistory(@PathVariable String sessionId) {
        // chatMemoryStore.getMessages(sessionId);
        return Mono.just(List.of(
            Map.of("role", "user", "content", "你好，请列出2025架构设计纲要"),
            Map.of("role", "assistant", "content", "好的，基于您的企业知识库...")
        ));
    }

    /**
     * 前端用户手动触发清除上下文环境/新建空白 Session
     */
    @DeleteMapping("/{sessionId}")
    public Mono<Void> clearSessionMemory(@PathVariable String sessionId) {
        // chatMemoryStore.deleteMessages(sessionId);
        return Mono.empty();
    }
    
    /**
     * 拉取当前登入用户由大模型抽象提取过的“长期偏好记忆”（System Prompt 层记忆）
     */
    @GetMapping("/user/memory")
    public Mono<Map<String, Object>> getUserGlobalMemory() {
        // String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        // userGlobalMemoryRepository.findByUserId(userId);
        return Mono.just(Map.of(
            "userId", "currentUser",
            "extractedPreferences", "用户喜欢Python，希望回答携带详细步骤代码。不希望返回过长的文本。"
        ));
    }
}
