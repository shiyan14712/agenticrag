package com.yoswell.agenticrag.core.agent.tool;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.alibaba.ttl.TtlRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.web.security.context.TenantContextHolder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.constants.ToolExecutionConstants;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

@Component
public class PreferenceTool {

    private static final Logger log = LoggerFactory.getLogger(PreferenceTool.class);
    private static final Duration PERSIST_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern PREFERENCE_KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final int MAX_PREFERENCE_VALUE_LENGTH = 200;

    private final UserGlobalMemoryMapper userGlobalMemoryMapper;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    public PreferenceTool(UserGlobalMemoryMapper userGlobalMemoryMapper,
                          RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.userGlobalMemoryMapper = userGlobalMemoryMapper;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    @Tool("save_user_preference")
    public String saveUserPreference(
            @P("Preference key in snake_case. Preferred keys: ending_phrase, response_language, response_style, response_length, code_focus. Expressions with clear semantics like these.")
            String preferenceKey,
            @P("Preference value for the selected key. Keep concise and concrete, max 200 chars. Example: meow")
            String preferenceValue) {
        String currentUserId = resolveCurrentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            log.warn("[PreferenceTool] Preference persistence rejected: missing authenticated principal");
            return ToolExecutionConstants.markFailed(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
        }

        if (preferenceKey == null || preferenceKey.isBlank() || preferenceValue == null || preferenceValue.isBlank()) {
            log.warn("[PreferenceTool] Preference persistence rejected due to invalid arguments: key={}, valuePresent={}",
                    preferenceKey, preferenceValue != null && !preferenceValue.isBlank());
            return ToolExecutionConstants.markFailed("Preference key/value must not be blank.");
        }

        String normalizedKey = normalizePreferenceKey(preferenceKey);
        if (normalizedKey == null) {
            log.warn("[PreferenceTool] Preference persistence rejected due to invalid key format: key={}", preferenceKey);
            return ToolExecutionConstants.markFailed(
                    "Invalid preference key format. Use snake_case, e.g. ending_phrase or response_style.");
        }

        String normalizedValue = preferenceValue.trim();
        if (normalizedValue.length() > MAX_PREFERENCE_VALUE_LENGTH) {
            log.warn("[PreferenceTool] Preference persistence rejected due to oversized value: key={}, length={}",
                    normalizedKey, normalizedValue.length());
            return ToolExecutionConstants.markFailed("Preference value is too long. Maximum length is 200 characters.");
        }

        log.info("[PreferenceTool] Persisting user preference asynchronously: [{}={} for user {}]",
                normalizedKey, normalizedValue, currentUserId);

        CompletableFuture<String> callback = new CompletableFuture<>();
        Runnable task = TtlRunnable.get(
                () -> callback.complete(persistPreferenceWithVerification(currentUserId, normalizedKey, normalizedValue)));
        Thread.ofVirtual()
                .name("preference-persist[" + currentUserId + "]-" + normalizedKey)
                .start(task);

        try {
            return callback.get(PERSIST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            log.error("[PreferenceTool] Preference persistence timed out: userId={}, key={}", currentUserId, normalizedKey,
                    timeoutException);
            return ToolExecutionConstants.markFailed("Preference persistence timed out.");
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.error("[PreferenceTool] Preference persistence interrupted: userId={}, key={}", currentUserId, normalizedKey,
                    interruptedException);
            return ToolExecutionConstants.markFailed("Preference persistence interrupted.");
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            String failureMessage = cause == null ? executionException.getMessage() : cause.getMessage();
            log.error("[PreferenceTool] Preference persistence execution failed: userId={}, key={}", currentUserId, normalizedKey,
                    executionException);
            return ToolExecutionConstants.markFailed("Preference persistence failed: " + failureMessage);
        }
    }

    private String persistPreferenceWithVerification(String userId, String preferenceKey, String preferenceValue) {
        try {
            UserGlobalMemory memory = new UserGlobalMemory();
            memory.setUserId(userId);
            memory.setPreferenceKey(preferenceKey);
            memory.setPreferenceValue(preferenceValue);

            int affectedRows = userGlobalMemoryMapper.insert(memory);
            if (affectedRows <= 0 || memory.getId() == null) {
                log.error("[PreferenceTool] Preference persistence callback failed: insert returned no rows. userId={}, key={}",
                        userId, preferenceKey);
                return ToolExecutionConstants.markFailed("Preference save failed: database insert affected 0 rows.");
            }

            Long persistedRows = userGlobalMemoryMapper.selectCount(new LambdaQueryWrapper<UserGlobalMemory>()
                    .eq(UserGlobalMemory::getId, memory.getId())
                    .eq(UserGlobalMemory::getUserId, userId));
            if (persistedRows == null || persistedRows <= 0) {
                log.error("[PreferenceTool] Preference persistence callback failed: insert verification missing row. userId={}, key={}, id={}",
                        userId, preferenceKey, memory.getId());
                return ToolExecutionConstants.markFailed("Preference save failed: callback verification did not find persisted record.");
            }

            log.info("[PreferenceTool] Preference persisted successfully: userId={}, key={}, id={}",
                    userId, preferenceKey, memory.getId());
            return ToolExecutionConstants.markSuccess("Preference saved successfully.");
        } catch (Exception exception) {
            log.error("[PreferenceTool] Preference persistence callback failed with exception: userId={}, key={}",
                    userId, preferenceKey, exception);
            return ToolExecutionConstants.markFailed("Preference save failed: " + exception.getMessage());
        }
    }

    private String resolveCurrentUserId() {
        // 1️⃣ 优先使用会话级快照（由 ChatOrchestrator 在 ReAct 循环入口注册）
        TenantUser sessionBoundUser = ragRetrievalContextHolder.currentTenantUser().orElse(null);

        // 2️⃣ Spring SecurityContext（在请求主线程上有效，子线程可能为空）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        TenantUser securityUser = null;
        if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
            securityUser = user;
        }

        // 3️⃣ TTL 传播层（子虚拟线程 / 线程池任务内的看护脶）
        TenantUser ttlUser = TenantContextHolder.get();

        if (sessionBoundUser != null) {
            if (securityUser != null && !sameIdentity(sessionBoundUser, securityUser)) {
                log.warn("[PreferenceTool] Auth context conflict detected. sessionUserId={}, securityUserId={}",
                        sessionBoundUser.getUserId(), securityUser.getUserId());
                return null;
            }
            return sessionBoundUser.getUserId();
        }

        if (securityUser != null) {
            return securityUser.getUserId();
        }

        if (ttlUser != null) {
            log.debug("[PreferenceTool] Resolved userId via TenantContextHolder (TTL): userId={}", ttlUser.getUserId());
            return ttlUser.getUserId();
        }

        if (authentication != null && authentication.isAuthenticated()) {
            String authenticationName = authentication.getName();
            if (authenticationName != null && !authenticationName.isBlank() && !"anonymousUser".equals(authenticationName)) {
                return authenticationName;
            }
        }
        return null;
    }

    private boolean sameIdentity(TenantUser left, TenantUser right) {
        return left.getUserId().equals(right.getUserId())
                && left.getTenantId().equals(right.getTenantId());
    }

    private String normalizePreferenceKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String candidate = rawKey.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("_+", "_");

        if (candidate.isBlank()) {
            return null;
        }

        candidate = switch (candidate) {
            case "endingphrase" -> "ending_phrase";
            case "language" -> "response_language";
            case "style", "tone" -> "response_style";
            case "length", "verbosity" -> "response_length";
            case "codefocus", "focus_code", "core_code_only" -> "code_focus";
            default -> candidate;
        };

        if (!PREFERENCE_KEY_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}
