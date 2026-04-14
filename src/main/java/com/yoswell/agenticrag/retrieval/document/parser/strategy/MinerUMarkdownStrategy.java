package com.yoswell.agenticrag.retrieval.document.parser.strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

/**
 * 面向 Markdown 风格文本的分块（Chunking）策略
 *
 * <p>本策略针对<strong>以.md文档为首的结构性强</strong>的文档做chunk</p>
 *
 * <p>【架构说明】：</p>
 * <p>它优先保留标题层级信息，再在每个 section 内按长度切块，
 * 这样检索结果能兼顾结构语义和 chunk 粒度</p>
 * <p>注意：MinerU OCR PDF 解析引擎不在当前模块和 Java 项目中执行！
 * 这个策略类仅负责对 Python Worker 解析出来并存入 MinIO 的高精度 Markdown 文本，
 * 进行离线的语义切块和向量化准备阶段</p>
 */
@Component
public class MinerUMarkdownStrategy implements DocumentParserStrategy {
    private static final Pattern ATX_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern SETEXT_H1_PATTERN = Pattern.compile("^\\s*=+\\s*$");
    private static final Pattern SETEXT_H2_PATTERN = Pattern.compile("^\\s*-+\\s*$");
    private static final Pattern FENCE_PATTERN = Pattern.compile("^\\s*(```+|~~~+).*$");
    private static final int DEFAULT_CHUNK_SIZE = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    /**
     * 解析 Markdown 文本并生成结构化 chunk
     *
     * @param source 解析输入
     * @return 解析结果
     */
    @Override
    public ParsedDocument parse(DocumentParseSource source) {
        List<String> chunks = chunkMarkdown(source.content(), DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        return new ParsedDocument(
                source.fileUrl(),
                source.fileName(),
                toParsedChunks(source, chunks)
        );
    }

    /**
     * 把 Markdown 文本先拆 section，再按长度和重叠窗口切块
     *
     * @param markdown Markdown 内容
     * @param chunkSize 目标 chunk 长度
     * @param overlap 相邻 chunk 的重叠长度
     * @return 文本块列表
     */
    static List<String> chunkMarkdown(String markdown, int chunkSize, int overlap) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        int normalizedChunkSize = Math.max(1, chunkSize);
        int normalizedOverlap = Math.max(0, Math.min(overlap, normalizedChunkSize - 1));
        List<Section> sections = splitSections(markdown);
        if (sections.isEmpty()) {
            return List.of();
        }

        ArrayList<String> chunks = new ArrayList<>(sections.size());
        for (Section section : sections) {
            appendChunks(section, normalizedChunkSize, normalizedOverlap, chunks);
        }
        return List.copyOf(chunks);
    }

    /**
     * 为每个文本块补充稳定 chunkId 和顺序号
     *
     * @param source 解析输入
     * @param chunks 文本块内容
     * @return 标准化的 chunk 记录
     */
    private static List<ParsedDocumentChunk> toParsedChunks(DocumentParseSource source, List<String> chunks) {
        ArrayList<ParsedDocumentChunk> parsedChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            parsedChunks.add(new ParsedDocumentChunk(
                    deterministicChunkId(source, index),
                    index,
                    chunks.get(index)
            ));
        }
        return List.copyOf(parsedChunks);
    }

    /**
     * 通过文件地址、文件名和序号生成稳定的 chunkId
     *
     * @param source 解析输入
     * @param index chunk 顺序
     * @return 稳定 chunkId
     */
    private static String deterministicChunkId(DocumentParseSource source, int index) {
        String seed = source.fileUrl() + "|" + source.fileName() + "|" + index;
        return "chk-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按标题层级把 Markdown 拆成 section
     *
     * @param markdown Markdown 内容
     * @return section 列表
     */
    private static List<Section> splitSections(String markdown) {
        String normalizedMarkdown = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedMarkdown.split("\n", -1);
        ArrayList<Section> sections = new ArrayList<>();
        ArrayList<Heading> headingStack = new ArrayList<>(6);
        StringBuilder body = new StringBuilder(normalizedMarkdown.length());
        boolean inFenceBlock = false;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];

            if (FENCE_PATTERN.matcher(line).matches()) {
                inFenceBlock = !inFenceBlock;
                appendLine(body, line);
                continue;
            }

            if (!inFenceBlock) {
                Matcher atxHeadingMatcher = ATX_HEADING_PATTERN.matcher(line);
                if (atxHeadingMatcher.matches()) {
                    addSection(sections, headingStack, body);
                    pushHeading(headingStack, atxHeadingMatcher.group(2).trim(), atxHeadingMatcher.group(1).length());
                    continue;
                }

                int setextLevel = resolveSetextLevel(lines, index);
                if (setextLevel > 0 && !line.isBlank()) {
                    addSection(sections, headingStack, body);
                    pushHeading(headingStack, line.trim(), setextLevel);
                    index++;
                    continue;
                }
            }

            appendLine(body, line);
        }

        String trailingBody = body.toString().strip();
        addSection(sections, headingStack, body);
        if (trailingBody.isEmpty() && !headingStack.isEmpty()
                && (sections.isEmpty() || !sections.get(sections.size() - 1).headings().equals(headingStack))) {
            sections.add(new Section(List.copyOf(headingStack), ""));
        }

        return sections;
    }

    /**
     * 在单个 section 内按长度切块，并把标题路径前缀拼进 chunk
     *
     * @param section 当前 section
     * @param chunkSize 目标 chunk 长度
     * @param overlap 相邻 chunk 重叠长度
     * @param chunks 输出集合
     */
    private static void appendChunks(Section section, int chunkSize, int overlap, List<String> chunks) {
        String prefix = formatHeadingPath(section.headings());
        String body = section.body().strip();

        if (body.isEmpty()) {
            if (!prefix.isEmpty()) {
                chunks.add(prefix);
            }
            return;
        }

        int availableBodyLength = Math.max(1, chunkSize - prefix.length() - (prefix.isEmpty() ? 0 : 2));
        int effectiveOverlap = Math.min(overlap, Math.max(0, availableBodyLength - 1));
        int start = 0;

        while (start < body.length()) {
            int maxEnd = Math.min(body.length(), start + availableBodyLength);
            int end = findChunkEnd(body, start, maxEnd);
            if (end <= start) {
                end = maxEnd;
            }

            String chunkBody = body.substring(start, end).strip();
            if (!chunkBody.isEmpty()) {
                chunks.add(prefix.isEmpty() ? chunkBody : prefix + "\n\n" + chunkBody);
            }

            if (end >= body.length()) {
                break;
            }

            start = Math.max(end - effectiveOverlap, start + 1);
            while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
                start++;
            }
        }
    }

    /**
     * 把当前累积正文收束成一个 section
     *
     * @param sections 输出 section 集合
     * @param headingStack 当前标题栈
     * @param body 当前累积正文
     */
    private static void addSection(List<Section> sections, List<Heading> headingStack, StringBuilder body) {
        String content = body.toString().strip();
        if (!content.isEmpty()) {
            sections.add(new Section(List.copyOf(headingStack), content));
        }
        body.setLength(0);
    }

    /**
     * 维护标题栈，保证层级路径始终表示当前位置
     *
     * @param headingStack 当前标题栈
     * @param title 新标题文本
     * @param level 新标题级别
     */
    private static void pushHeading(List<Heading> headingStack, String title, int level) {
        while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).level() >= level) {
            headingStack.removeLast();
        }
        headingStack.add(new Heading(level, title));
    }

    /**
     * 判断当前行下一行是否构成 setext 风格标题
     *
     * @param lines 全部文本行
     * @param index 当前行下标
     * @return 标题级别，0 表示不是 setext 标题
     */
    private static int resolveSetextLevel(String[] lines, int index) {
        if (index + 1 >= lines.length) {
            return 0;
        }

        String underline = lines[index + 1];
        if (SETEXT_H1_PATTERN.matcher(underline).matches()) {
            return 1;
        }
        if (SETEXT_H2_PATTERN.matcher(underline).matches()) {
            return 2;
        }
        return 0;
    }

    /**
     * 向正文缓冲区追加一行文本
     *
     * @param body 正文缓冲区
     * @param line 当前文本行
     */
    private static void appendLine(StringBuilder body, String line) {
        if (!body.isEmpty()) {
            body.append('\n');
        }
        body.append(line);
    }

    /**
     * 优先在自然断点上结束当前 chunk
     *
     * @param body 当前 section 正文
     * @param start chunk 起始位置
     * @param maxEnd 最大结束位置
     * @return 实际结束位置
     */
    private static int findChunkEnd(String body, int start, int maxEnd) {
        if (maxEnd >= body.length()) {
            return body.length();
        }

        int minEnd = Math.max(start + 1, maxEnd - 120);
        for (int index = maxEnd - 1; index >= minEnd; index--) {
            char current = body.charAt(index);
            if (current == '\n' || Character.isWhitespace(current)) {
                return index;
            }
        }
        return maxEnd;
    }

    /**
     * 把标题栈格式化成多行 Markdown 标题前缀
     *
     * @param headings 当前标题路径
     * @return 标题路径文本
     */
    private static String formatHeadingPath(List<Heading> headings) {
        if (headings.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Heading heading : headings) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            for (int count = 0; count < heading.level(); count++) {
                builder.append('#');
            }
            builder.append(' ').append(heading.title());
        }
        return builder.toString();
    }

    /**
     * 标题节点
     *
     * @param level 标题级别
     * @param title 标题文本
     */
    private record Heading(int level, String title) {
    }

    /**
     * section 节点，表示一段标题路径下的正文内容
     *
     * @param headings 标题路径
     * @param body 正文内容
     */
    private record Section(List<Heading> headings, String body) {
    }
}
