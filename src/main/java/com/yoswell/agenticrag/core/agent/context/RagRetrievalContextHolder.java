package com.yoswell.agenticrag.core.agent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.yoswell.agenticrag.core.agent.dto.CitationDTO;
import com.yoswell.agenticrag.core.agent.dto.RagSearchResultDTO;
import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;

@Component
public class RagRetrievalContextHolder {

    private final InheritableThreadLocal<String> activeSessionId = new InheritableThreadLocal<>();
    private final Map<String, RagSearchResultDTO> sessionResults = new ConcurrentHashMap<>();

    public AutoCloseable bindSession(String sessionId) {
        activeSessionId.set(sessionId);
        return () -> activeSessionId.remove();
    }

    public void publish(RagSearchResultDTO result) {
        String sessionId = activeSessionId.get();
        if (sessionId == null) {
            return;
        }

        sessionResults.merge(sessionId, result, this::mergeResults);
    }

    public Optional<RagSearchResultDTO> consume(String sessionId) {
        return Optional.ofNullable(sessionResults.remove(sessionId));
    }

    private RagSearchResultDTO mergeResults(RagSearchResultDTO current, RagSearchResultDTO incoming) {
        Map<String, RetrievedChunkDTO> mergedChunks = new LinkedHashMap<>();
        current.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));
        incoming.retrievedChunks().forEach(chunk -> mergedChunks.put(chunk.chunkId(), chunk));

        Map<String, CitationDTO> mergedCitations = new LinkedHashMap<>();
        current.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));
        incoming.citations().forEach(citation -> mergedCitations.put(citation.chunkId(), citation));

        return new RagSearchResultDTO(
                incoming.observation(),
                List.copyOf(new ArrayList<>(mergedChunks.values())),
                List.copyOf(new ArrayList<>(mergedCitations.values()))
        );
    }
}
