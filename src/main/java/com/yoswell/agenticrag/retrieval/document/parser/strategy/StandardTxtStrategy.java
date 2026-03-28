package com.yoswell.agenticrag.retrieval.document.parser.strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.retrieval.document.parser.model.DocumentParseSource;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocument;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

@Component
public class StandardTxtStrategy implements DocumentParserStrategy {

    private static final int DEFAULT_CHUNK_SIZE = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;

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
