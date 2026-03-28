package com.yoswell.agenticrag.platform.session.controller;

import java.util.Map;

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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoswell.agenticrag.platform.session.dto.SessionCreateRequest;
import com.yoswell.agenticrag.platform.session.dto.SessionUpdateRequest;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.service.SessionContextSwitcher;
import com.yoswell.agenticrag.platform.session.service.SessionService;
import com.yoswell.agenticrag.util.SecurityUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionContextSwitcher sessionSwitcher;

    public SessionController(SessionService sessionService, SessionContextSwitcher sessionSwitcher) {
        this.sessionService = sessionService;
        this.sessionSwitcher = sessionSwitcher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ChatSession> createSession(@RequestBody(required = false) SessionCreateRequest request) {
        return Mono.fromCallable(() -> sessionService.createSession(SecurityUtils.getCurrentUserId(), request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<Page<ChatSession>> getSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ACTIVE") String status) {
        return Mono.fromCallable(() -> sessionService.getSessions(SecurityUtils.getCurrentUserId(), status, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        String userId = SecurityUtils.getCurrentUserId();
        return Mono.fromCallable(() -> sessionService.getSessionDetails(sessionId, userId, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{sessionId}/activate")
    public Mono<ChatSession> activateSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> sessionSwitcher.activateSession(sessionId, SecurityUtils.getCurrentUserId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/{sessionId}")
    public Mono<ChatSession> updateSession(
            @PathVariable String sessionId,
            @RequestBody SessionUpdateRequest request) {
        return Mono.fromCallable(() -> sessionService.updateSession(sessionId, SecurityUtils.getCurrentUserId(), request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "archive") String mode) {
        return Mono.<Void>fromRunnable(() -> {
            sessionService.deleteSession(sessionId, SecurityUtils.getCurrentUserId(), mode);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
