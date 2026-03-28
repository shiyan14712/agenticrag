package com.yoswell.agenticrag.core.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;

import tools.jackson.databind.json.JsonMapper;

class RerankerClientTest {

    @Test
    void rerankFallsBackToOriginalOrderWhenEndpointFails() {
        RerankerClient client = new RerankerClient(
                JsonMapper.builder().findAndAddModules().build(),
                "http://127.0.0.1:1/rerank",
                "",
                "jina-reranker-v2-base-multilingual"
        );

        List<RetrievedChunkDTO> original = List.of(
                new RetrievedChunkDTO("chk-1", "doc-1", "doc-1.txt", "tenant-a", "kb-1", List.of("ROLE_USER"), 0, "alpha", 0.9),
                new RetrievedChunkDTO("chk-2", "doc-2", "doc-2.txt", "tenant-a", "kb-1", List.of("ROLE_USER"), 1, "beta", 0.8)
        );

        List<RetrievedChunkDTO> reranked = client.rerank("query", original);

        assertThat(reranked).containsExactlyElementsOf(original);
    }
}
