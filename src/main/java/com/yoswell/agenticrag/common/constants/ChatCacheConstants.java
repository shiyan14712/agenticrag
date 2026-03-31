package com.yoswell.agenticrag.common.constants;

/**
 * 聊天与会话相关的缓存常量
 */
public class ChatCacheConstants {

    private ChatCacheConstants() {
        // 私有化构造器
    }

    /**
     * 会话标题异步生成标记的 Redis Key 前缀
     */
    public static final String SESSION_TITLE_GEN_PREFIX = "chat:session:title_gen:";

}