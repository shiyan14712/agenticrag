package com.yoswell.agenticrag.retrieval.document.parser.model;

/**
 * 传给解析策略的统一输入载体。
 *
 * @param fileUrl 源文件地址，主要用于日志追踪和稳定生成 chunkId
 * @param fileName 源文件名
 * @param fileExtension 源文件扩展名
 * @param content 已经读取到内存中的文本内容
 */
public record DocumentParseSource(
        String fileUrl,
        String fileName,
        String fileExtension,
        String content) {
}
