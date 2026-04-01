package com.yoswell.agenticrag.platform.session.constants;

/**
 * 会话状态常量
 *
 * <p>状态值定义：</p>
 * <p>0: ACTIVE（活跃）</p>
 * <p>1: ARCHIVED（已归档）</p>
 * <p>2: DELETED（已删除）</p>
 */
public final class SessionStatusConstants {

    public static final int ACTIVE = 0;
    public static final int ARCHIVED = 1;
    public static final int DELETED = 2;

    public static final String ACTIVE_VALUE = "0";
    public static final String ARCHIVED_VALUE = "1";
    public static final String DELETED_VALUE = "2";

    private SessionStatusConstants() {
    }

    public static boolean isValidStatus(Integer status) {
        return status != null
                && (status == ACTIVE || status == ARCHIVED || status == DELETED);
    }

    public static boolean isValidDeleteMode(Integer mode) {
        return mode != null && (mode == ARCHIVED || mode == DELETED);
    }

    public static Integer parseStatus(String rawStatus, int defaultStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return defaultStatus;
        }

        String normalized = rawStatus.trim().toUpperCase();
        return switch (normalized) {
            case ACTIVE_VALUE, "ACTIVE" -> ACTIVE;
            case ARCHIVED_VALUE, "ARCHIVED", "ARCHIVE" -> ARCHIVED;
            case DELETED_VALUE, "DELETED", "DELETE", "PERMANENT" -> DELETED;
            default -> defaultStatus;
        };
    }

    public static Integer parseDeleteMode(String rawMode, int defaultMode) {
        Integer parsed = parseStatus(rawMode, defaultMode);
        if (parsed != null && isValidDeleteMode(parsed)) {
            return parsed;
        }
        return defaultMode;
    }
}