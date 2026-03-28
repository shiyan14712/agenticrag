package com.yoswell.agenticrag.controller;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yoswell.agenticrag.dto.SessionCreateRequest;
import com.yoswell.agenticrag.dto.SessionUpdateRequest;
import com.yoswell.agenticrag.entity.ChatMessage;
import com.yoswell.agenticrag.entity.ChatSession;
import com.yoswell.agenticrag.service.session.SessionContextSwitcher;
import com.yoswell.agenticrag.service.session.SessionService;
import com.yoswell.agenticrag.security.TenantUser;
import org.springframework.security.core.context.SecurityContextHolder;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionContextSwitcher sessionSwitcher;

    public SessionController(SessionService sessionService, SessionContextSwitcher sessionSwitcher) {
        this.sessionService = sessionService;
        this.sessionSwitcher = sessionSwitcher;
    }

    private String getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof TenantUser) {
            return ((TenantUser) principal).getUserId();
        }
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ChatSession> createSession(@RequestBody(required = false) SessionCreateRequest request) {
        return Mono.fromCallable(() -> sessionService.createSession(getCurrentUserId(), request));
    }

    @GetMapping
    public Mono<Page<ChatSession>> getSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        return Mono.fromCallable(() -> sessionService.getSessions(getCurrentUserId(), status, page, size));
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Mono.fromCallable(() -> {
            ChatSession session = sessionService.getSession(sessionId, getCurrentUserId());
            Page<ChatMessage> messages = sessionService.getSessionMessages(sessionId, getCurrentUserId(), page, size);
            return Map.of(
                "session", session,
                "messages", messages
            );
        });
    }

    @PutMapping("/{sessionId}/activate")
    public Mono<ChatSession> activateSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> sessionSwitcher.activateSession(sessionId, getCurrentUserId()));
    }

    @PatchMapping("/{sessionId}")
    public Mono<ChatSession> updateSession(
            @PathVariable String sessionId,
            @RequestBody SessionUpdateRequest request) {
        return Mono.fromCallable(() -> sessionService.updateSession(sessionId, getCurrentUserId(), request));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "archive") String mode) {
        return Mono.fromRunnable(() -> {
            sessionService.deleteSession(sessionId, getCurrentUserId(), mode);
        });
    }
}
