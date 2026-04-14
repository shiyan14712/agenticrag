package com.yoswell.agenticrag.retrieval.document.parser.strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

/**
 * 面向纯文本文件的解析策略
 *
 * <p>本策略意在针对那些没有显式标题结构的纯文本做处理，因此这里只做长度切块和自然断点收束</p>
 */
@Component
public class StandardTxtStrategy implements DocumentParserStrategy {

    private static final int DEFAULT_CHUNK_SIZE = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    /**
     * 解析纯文本内容并生成 chunk
     *
     * @param source 解析输入
     * @return 解析结果
     */
    @Override
    public ParsedDocument parse(DocumentParseSource source) {
        List<String> chunks = chunkPlainText(source.content(), DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        ArrayList<ParsedDocumentChunk> parsedChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            String seed = source.fileUrl() + "|" + source.fileName() + "|" + index;
            parsedChunks.add(new ParsedDocumentChunk(
                    "chk-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
                    index,
                    chunks.get(index)
            ));
        }
        return new ParsedDocument(source.fileUrl(), source.fileName(), List.copyOf(parsedChunks));
    }

    /**
     * 按长度和重叠窗口切分纯文本
     *
     * @param content 原始文本
     * @param chunkSize 目标 chunk 长度
     * @param overlap 相邻 chunk 重叠长度
     * @return 文本块列表
     */
    static List<String> chunkPlainText(String content, int chunkSize, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return List.of();
        }

        int normalizedChunkSize = Math.max(1, chunkSize);
        int normalizedOverlap = Math.max(0, Math.min(overlap, normalizedChunkSize - 1));
        ArrayList<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < normalized.length()) {
            int maxEnd = Math.min(normalized.length(), start + normalizedChunkSize);
            int end = findChunkEnd(normalized, start, maxEnd);
            if (end <= start) {
                end = maxEnd;
            }

            String chunk = normalized.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            start = Math.max(end - normalizedOverlap, start + 1);
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }

        return List.copyOf(chunks);
    }

    /**
     * 优先在自然断点上截断当前 chunk
     *
     * @param content 规范化后的纯文本
     * @param start chunk 起始位置
     * @param maxEnd 最大结束位置
     * @return 实际结束位置
     */
    private static int findChunkEnd(String content, int start, int maxEnd) {
        if (maxEnd >= content.length()) {
            return content.length();
        }

        int minEnd = Math.max(start + 1, maxEnd - 160);
        for (int index = maxEnd - 1; index >= minEnd; index--) {
            char current = content.charAt(index);
            if (current == '\n' || Character.isWhitespace(current)) {
                return index;
            }
        }
        return maxEnd;
    }
}
