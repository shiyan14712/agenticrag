package com.yoswell.agenticrag.platform.session.constants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 会话状态枚举
 *
 * <p>状态值定义：</p>
 * <p>0: ACTIVE（活跃）</p>
 * <p>1: ARCHIVED（已归档）</p>
 * <p>2: DELETED（已删除）</p>
 */
@RequiredArgsConstructor
public enum SessionStatusConstants {

    ACTIVE(0, "活跃"),
    ARCHIVED(1, "已归档"),
    DELETED(2, "已删除");

    @Getter
    private final int code;
    
    @Getter
    private final String description;

}