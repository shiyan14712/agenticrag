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
    private static final int START_BOUNDARY_LOOKAROUND = 40;

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

            start = adjustChunkStart(normalized, start, end, normalizedOverlap);
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

    /**
     * 尽量把下一个 chunk 的起点贴近自然断点，避免 overlap 从单词中间开始。
     *
     * @param content 规范化后的纯文本
     * @param previousStart 当前 chunk 的起点
     * @param previousEnd 当前 chunk 的终点
     * @param overlap 重叠窗口长度
     * @return 调整后的下一个 chunk 起点
     */
    private static int adjustChunkStart(String content, int previousStart, int previousEnd, int overlap) {
        int lowerBound = previousStart + 1;
        int tentativeStart = Math.max(previousEnd - overlap, lowerBound);
        int adjustedStart = alignToWordBoundary(content, tentativeStart, lowerBound);
        return skipLeadingWhitespace(content, adjustedStart);
    }

    /**
     * 在有限窗口内把起点回拉到单词边界；如果附近没有合适边界，就保留原位置。
     *
     * @param content 规范化后的纯文本
     * @param candidateStart 候选起点
     * @param lowerBound 最小允许起点
     * @return 对齐后的起点
     */
    private static int alignToWordBoundary(String content, int candidateStart, int lowerBound) {
        if (candidateStart <= lowerBound || candidateStart >= content.length()) {
            return candidateStart;
        }

        if (Character.isWhitespace(content.charAt(candidateStart))) {
            return candidateStart;
        }

        int backwardLimit = Math.max(lowerBound, candidateStart - START_BOUNDARY_LOOKAROUND);
        for (int index = candidateStart; index > backwardLimit; index--) {
            if (Character.isWhitespace(content.charAt(index - 1))) {
                return index;
            }
        }

        int forwardLimit = Math.min(content.length(), candidateStart + START_BOUNDARY_LOOKAROUND);
        for (int index = candidateStart; index < forwardLimit; index++) {
            if (Character.isWhitespace(content.charAt(index))) {
                return index + 1;
            }
        }

        return candidateStart;
    }

    /**
     * 跳过 chunk 起点处的空白字符，避免产生空前缀。
     *
     * @param content 规范化后的纯文本
     * @param start 候选起点
     * @return 跳过空白后的起点
     */
    private static int skipLeadingWhitespace(String content, int start) {
        int adjustedStart = start;
        while (adjustedStart < content.length() && Character.isWhitespace(content.charAt(adjustedStart))) {
            adjustedStart++;
        }
        return adjustedStart;
    }
}
