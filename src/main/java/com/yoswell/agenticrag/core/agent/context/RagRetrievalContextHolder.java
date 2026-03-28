package com.yoswell.agenticrag.core.agent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.core.agent.dto.CitationDto;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResult;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunk;

@Component
public class RagRetrievalContextHolder {

    private final InheritableThreadLocal<String> activeSessionId = new InheritableThreadLocal<>();
    private final Map<String, RagSearchResult> sessionResults = new ConcurrentHashMap<>();

    public AutoCloseable bindSession(String sessionId) {
        activeSessionId.set(sessionId);
        return () -> activeSessionId.remove();
    }

    public void publish(RagSearchResult result) {
        String sessionId = activeSessionId.get();
        if (sessionId == null) {
            return;
        }

        sessionResults.merge(sessionId, result, this::mergeResults);
    }

    public Optional<RagSearchResult> consume(String sessionId) {
        return Optional.ofNullable(sessionResults.remove(sessionId));
    }

    private RagSearchResult mergeResults(RagSearchResult current, RagSearchResult incoming) {
        Map<String, RetrievedChunk> mergedChunks = new LinkedHashMap<>();
        current.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));
        incoming.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));

        Map<String, CitationDto> mergedCitations = new LinkedHashMap<>();
        current.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));
        incoming.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));

        return new RagSearchResult(
                incoming.observation(),
                List.copyOf(new ArrayList<>(mergedChunks.values())),
                List.copyOf(new ArrayList<>(mergedCitations.values()))
        );
    }
}
