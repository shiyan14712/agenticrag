package com.yoswell.agenticrag.retrieval.document.parser.strategy;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class MinerUMarkdownStrategy implements DocumentParserStrategy {
    private static final Pattern ATX_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern SETEXT_H1_PATTERN = Pattern.compile("^\\s*=+\\s*$");
    private static final Pattern SETEXT_H2_PATTERN = Pattern.compile("^\\s*-+\\s*$");
    private static final Pattern FENCE_PATTERN = Pattern.compile("^\\s*(```+|~~~+).*$");
    private static final int DEFAULT_CHUNK_SIZE = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

    private volatile List<String> lastChunks = List.of();

    @Override
    public void parse(String fileUrl) {
        Path path = resolvePath(fileUrl);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Markdown file does not exist: " + fileUrl);
        }

        lastChunks = chunkMarkdown(readMarkdown(path), DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    List<String> getLastChunks() {
        return lastChunks;
    }

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

    private static void addSection(List<Section> sections, List<Heading> headingStack, StringBuilder body) {
        String content = body.toString().strip();
        if (!content.isEmpty()) {
            sections.add(new Section(List.copyOf(headingStack), content));
        }
        body.setLength(0);
    }

    private static void pushHeading(List<Heading> headingStack, String title, int level) {
        while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).level() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
        headingStack.add(new Heading(level, title));
    }

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

    private static void appendLine(StringBuilder body, String line) {
        if (body.length() > 0) {
            body.append('\n');
        }
        body.append(line);
    }

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

    private static String formatHeadingPath(List<Heading> headings) {
        if (headings.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (Heading heading : headings) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            for (int count = 0; count < heading.level(); count++) {
                builder.append('#');
            }
            builder.append(' ').append(heading.title());
        }
        return builder.toString();
    }

    private static Path resolvePath(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new IllegalArgumentException("File URL cannot be blank");
        }
        return fileUrl.startsWith("file:") ? Path.of(java.net.URI.create(fileUrl)) : Path.of(fileUrl);
    }

    private static String readMarkdown(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException("Failed to read markdown file: " + path, exception);
        }
    }

    private record Heading(int level, String title) {
    }

    private record Section(List<Heading> headings, String body) {
    }
}
