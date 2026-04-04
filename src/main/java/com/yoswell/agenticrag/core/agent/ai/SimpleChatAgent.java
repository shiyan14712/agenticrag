package com.yoswell.agenticrag.core.agent.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 标题生成智能体接口
 */
public interface SimpleChatAgent {
    
    // 用于每个Session初次对话后，生成一个标题，作为整个Session的Title
    @SystemMessage({
        "你是系统的标题生成助手。你的任务是从用户发出的文本中，提取或总结出一个极简的会话标题。",
        "规则1：不超过6个词语",
        "规则2：绝对不要包含任何标点符号、引号或特殊字符",
        "规则3：只需要输出陈述性的标题，严禁输出任何解释性话语、抱歉的话或多余的换行符。举例：对XXX的询问、XXX的探索"
    })
    @UserMessage("请为下面这段用户的发言生成一个标题：\n\n【用户发言开始】\n{{it}}\n【用户发言结束】\n\n请直接输出总结的标题：")
    String generateTitle(String userMessage);
}
