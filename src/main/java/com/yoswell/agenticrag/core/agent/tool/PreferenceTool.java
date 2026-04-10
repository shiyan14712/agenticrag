package com.yoswell.agenticrag.core.agent.tool;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.constants.ToolExecutionConstants;
import com.yoswell.agenticrag.core.agent.context.RagRetrievalContextHolder;
import com.yoswell.agenticrag.core.memory.entity.UserGlobalMemory;
import com.yoswell.agenticrag.core.memory.mapper.UserGlobalMemoryMapper;
import com.yoswell.agenticrag.web.security.model.TenantUser;

import dev.langchain4j.agent.tool.Tool;

@Component
public class PreferenceTool {

    private static final Logger log = LoggerFactory.getLogger(PreferenceTool.class);
    private static final Duration PERSIST_TIMEOUT = Duration.ofSeconds(5);

    private final UserGlobalMemoryMapper userGlobalMemoryMapper;
    private final RagRetrievalContextHolder ragRetrievalContextHolder;

    public PreferenceTool(UserGlobalMemoryMapper userGlobalMemoryMapper,
                          RagRetrievalContextHolder ragRetrievalContextHolder) {
        this.userGlobalMemoryMapper = userGlobalMemoryMapper;
        this.ragRetrievalContextHolder = ragRetrievalContextHolder;
    }

    @Tool("save_user_preference")
    public String saveUserPreference(String userId, String preferenceKey, String preferenceValue) {
        String currentUserId = resolveCurrentUserId();
        if (currentUserId == null || currentUserId.isBlank()) {
            log.warn("[PreferenceTool] Preference persistence rejected: missing authenticated principal");
            return ToolExecutionConstants.markFailed(ErrorCode.UNAUTHORIZED_ERROR.getMessage());
        }

        if (userId != null && !userId.isBlank() && !currentUserId.equals(userId)) {
            log.warn("[PreferenceTool] Preference persistence rejected due to user mismatch: requestUserId={}, currentUserId={}",
                    userId, currentUserId);
            return ToolExecutionConstants.markFailed("Preference save rejected due to unauthorized user mismatch.");
        }

        if (preferenceKey == null || preferenceKey.isBlank() || preferenceValue == null || preferenceValue.isBlank()) {
            log.warn("[PreferenceTool] Preference persistence rejected due to invalid arguments: key={}, valuePresent={}",
                    preferenceKey, preferenceValue != null && !preferenceValue.isBlank());
            return ToolExecutionConstants.markFailed("Preference key/value must not be blank.");
        }

        String normalizedKey = preferenceKey.trim();
        String normalizedValue = preferenceValue.trim();
        log.info("[PreferenceTool] Persisting user preference asynchronously: [{}={} for user {}]",
                normalizedKey, normalizedValue, currentUserId);

        CompletableFuture<String> callback = new CompletableFuture<>();
        Thread.startVirtualThread(() -> callback.complete(persistPreferenceWithVerification(currentUserId, normalizedKey, normalizedValue)));

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
        TenantUser sessionBoundUser = ragRetrievalContextHolder.currentTenantUser().orElse(null);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        TenantUser securityUser = null;
        if (authentication != null && authentication.getPrincipal() instanceof TenantUser user) {
            securityUser = user;
        }

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
}
