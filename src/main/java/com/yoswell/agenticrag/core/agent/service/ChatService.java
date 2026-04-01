package com.yoswell.agenticrag.core.agent.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yoswell.agenticrag.common.exception.BusinessException;
import com.yoswell.agenticrag.common.exception.ErrorCode;
import com.yoswell.agenticrag.core.agent.ai.SimpleChatAgent;
import com.yoswell.agenticrag.platform.session.entity.ChatSession;
import com.yoswell.agenticrag.platform.session.mapper.ChatSessionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 实现简单的LLM单论对话服务 基于 LangChain4J
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /**
     * 简单对话实现类
     */
    private final SimpleChatAgent simpleChatAgent;
    
    /**
     * 会话数据库操作 Mapper
     */
    private final ChatSessionMapper chatSessionMapper;

    /**
     * 根据用户输入的消息，调用SimpleChatAgent生成一个标题，并进行简单的清洗和截断处理，返回最终的标题结果。
     * @param message 用户输入的消息文本
     * @return 生成的标题文本，经过清洗和截断处理后的结果
     */
    public String generateTitle(String message) {
        String title = simpleChatAgent.generateTitle(message);
        
        // 滤除可能存在的双引号、单引号、换行符等前后空格，并截断防止部分大模型幻觉输出长篇大论
        if (title != null) {
            title = title.replaceAll("[\"\'\n\r]", "").trim();
            if (title.length() > 200) {
                title = title.substring(0, 195) + "...";
            }
        }
        return title;
    }

    /**
     * 从数据库中获取会话标题
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 会话标题
     */
    public String getSessionTitle(String sessionId, String userId) {
        LambdaQueryWrapper<ChatSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatSession::getSessionId, sessionId)
                    .eq(ChatSession::getUserId, userId)
                    .select(ChatSession::getTitle);
        ChatSession session = chatSessionMapper.selectOne(queryWrapper);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        return session.getTitle();
    }


    /**
     * 根据用户输入的消息，调用SimpleChatAgent生成一个标题，并落库
     * 
     * @param sessionId 会话ID
     * @param firstUserQueryContent 用户首轮提问内容
     */
    public void generateTitleAndSave(String sessionId, String firstUserQueryContent) {
        try {
            // 1. 调用大模型生成清洗好的标题
            String title = generateTitle(firstUserQueryContent);
            
            // 2. 更新到数据库
            ChatSession sessionUpdate = new ChatSession();
            sessionUpdate.setTitle(title);
            
            UpdateWrapper<ChatSession> wrapper = new UpdateWrapper<>();
            wrapper.eq("session_id", sessionId);
            
            chatSessionMapper.update(sessionUpdate, wrapper);
            log.info("[ChatService Title Generation] Successfully generated and saved title for session {}: {}", sessionId, title);
        } catch (Exception e) {
            log.error("[ChatService Title Generation] Failed to generate or save title for session {}", sessionId, e);
        }
    }
}
