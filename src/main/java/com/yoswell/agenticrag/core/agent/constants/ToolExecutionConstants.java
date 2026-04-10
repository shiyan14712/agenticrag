package com.yoswell.agenticrag.core.agent.constants;

/**
 * Tool execution marker constants and helpers.
 *
 * <p>These markers are returned by tool methods and consumed by the
 * orchestrator to render tool_result status consistently.</p>
 */
public final class ToolExecutionConstants {

    private ToolExecutionConstants() {
        // utility class
    }

    public static final String SUCCESS_PREFIX = "__TOOL_SUCCESS__";
    public static final String FAILED_PREFIX = "__TOOL_FAILED__";

    public static String markSuccess(String message) {
        return SUCCESS_PREFIX + " " + normalizeMessage(message);
    }

    public static String markFailed(String message) {
        return FAILED_PREFIX + " " + normalizeMessage(message);
    }

    public static boolean isFailedResult(String rawResult) {
        return hasPrefix(rawResult, FAILED_PREFIX);
    }

    public static boolean isSuccessResult(String rawResult) {
        return hasPrefix(rawResult, SUCCESS_PREFIX);
    }

    public static String stripMarker(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "";
        }

        String normalized = rawResult.trim();
        if (normalized.startsWith(FAILED_PREFIX)) {
            return normalized.substring(FAILED_PREFIX.length()).trim();
        }
        if (normalized.startsWith(SUCCESS_PREFIX)) {
            return normalized.substring(SUCCESS_PREFIX.length()).trim();
        }
        return normalized;
    }

    private static boolean hasPrefix(String rawResult, String prefix) {
        return rawResult != null && !rawResult.isBlank() && rawResult.trim().startsWith(prefix);
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}