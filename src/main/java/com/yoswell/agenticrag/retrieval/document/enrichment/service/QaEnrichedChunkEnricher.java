package com.yoswell.agenticrag.retrieval.document.enrichment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.enrichment.model.AmbiguousUnitResult;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.EnrichedChunk;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.EntityRegistryEntry;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.SupplementaryContextResult;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;

@Service
public class QaEnrichedChunkEnricher {

    private static final Logger log = LoggerFactory.getLogger(QaEnrichedChunkEnricher.class);

    private static final String AMBIGUITY_SYSTEM_PROMPT = """
            你是一个文本分析助手。请分析给定文本，找出所有脱离上下文后会产生歧义的信息单元：
            - 代词指代不明的（他、她、它、该、其、这、那）
            - 缩写或简称未展开的
            - 省略了主语或关键限定词的表述
            - 首次出现但未定义的专业术语或专有名词

            对每个模糊单元，输出它在原文中的原始表述和判定为模糊的原因。
            如果文本本身已经自包含、没有歧义，返回空列表。

            以 JSON 格式返回：{"ambiguousUnits": [{"mention": "...", "reason": "..."}]}
            """;

    private static final String CONTEXT_GEN_SYSTEM_PROMPT = """
            你是一个补充上下文生成助手。对于给定的模糊信息单元列表，请根据提供的实体注册表和相邻文本，
            为每个模糊单元生成一句简洁的补充陈述句，解释该单元的具体含义。

            以 JSON 格式返回：{"contextStatements": [{"mention": "...", "statement": "..."}]}
            如果某个单元无法从已有信息中消解，请跳过它（不要编造信息）。
            """;

    private static final String SUPPLEMENTARY_CONTEXT_HEADER = "\n[Supplementary Context]";

    private final ChatModel chatModel;

    public QaEnrichedChunkEnricher(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<EnrichedChunk> enrich(List<ParsedDocumentChunk> chunks,
                                      Map<String, EntityRegistryEntry> entityRegistry,
                                      String documentId) {

        log.info("[Special Chunk][QA_ENRICH] 开始 QA 增强: documentId={}, chunkCount={}",
                documentId, chunks.size());

        String registryContext = formatEntityRegistry(entityRegistry);
        List<EnrichedChunk> enrichedChunks = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            ParsedDocumentChunk chunk = chunks.get(i);
            String neighborContext = buildNeighborContext(chunks, i);

            try {
                AmbiguousUnitResult ambiguousUnits = identifyAmbiguousUnits(chunk.content());

                if (ambiguousUnits == null || ambiguousUnits.ambiguousUnits() == null
                        || ambiguousUnits.ambiguousUnits().isEmpty()) {
                    enrichedChunks.add(new EnrichedChunk(
                            chunk.chunkId(), chunk.chunkIndex(),
                            chunk.content(), chunk.content()));
                } else {
                    SupplementaryContextResult context = generateSupplementaryContext(
                            ambiguousUnits, registryContext, chunk.content(), neighborContext);

                    String enrichedContent = appendContext(chunk.content(), context);
                    enrichedChunks.add(new EnrichedChunk(
                            chunk.chunkId(), chunk.chunkIndex(),
                            chunk.content(), enrichedContent));
                }

                if ((i + 1) % 5 == 0 || i + 1 == chunks.size()) {
                    log.info("[Special Chunk][QA_ENRICH] 进度: documentId={}, {}/{}",
                            documentId, i + 1, chunks.size());
                }
            } catch (Exception e) {
                log.warn("[Special Chunk][QA_ENRICH] 增强失败，保留原文: documentId={}, chunkIndex={}",
                        documentId, chunk.chunkIndex(), e);
                enrichedChunks.add(new EnrichedChunk(
                        chunk.chunkId(), chunk.chunkIndex(),
                        chunk.content(), chunk.content()));
            }
        }

        log.info("[Special Chunk][QA_ENRICH] QA 增强完成: documentId={}, chunkCount={}",
                documentId, enrichedChunks.size());
        return enrichedChunks;
    }

    private AmbiguousUnitResult identifyAmbiguousUnits(String chunkContent) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(AMBIGUITY_SYSTEM_PROMPT),
                        UserMessage.from(chunkContent)))
                .responseFormat(ResponseFormat.JSON)
                .build();

        ChatResponse response = chatModel.chat(request);
        return parseJson(response.aiMessage().text(), AmbiguousUnitResult.class);
    }

    private SupplementaryContextResult generateSupplementaryContext(
            AmbiguousUnitResult ambiguousUnits, String registryContext,
            String chunkContent, String neighborContext) {

        String userPrompt = "## 实体注册表\n" + registryContext
                + "\n\n## 当前文本\n" + chunkContent
                + "\n\n## 相邻文本\n" + neighborContext
                + "\n\n## 需要消解的模糊单元\n"
                + ambiguousUnits.ambiguousUnits().stream()
                        .map(u -> "- " + u.mention() + "（" + u.reason() + "）")
                        .collect(Collectors.joining("\n"));

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(CONTEXT_GEN_SYSTEM_PROMPT),
                        UserMessage.from(userPrompt)))
                .responseFormat(ResponseFormat.JSON)
                .build();

        ChatResponse response = chatModel.chat(request);
        return parseJson(response.aiMessage().text(), SupplementaryContextResult.class);
    }

    private String appendContext(String originalContent, SupplementaryContextResult context) {
        if (context == null || context.contextStatements() == null
                || context.contextStatements().isEmpty()) {
            return originalContent;
        }

        StringBuilder builder = new StringBuilder(originalContent);
        builder.append(SUPPLEMENTARY_CONTEXT_HEADER);
        for (SupplementaryContextResult.ContextStatement stmt : context.contextStatements()) {
            builder.append("\n- ").append(stmt.mention()).append(": ").append(stmt.statement());
        }
        return builder.toString();
    }

    private String buildNeighborContext(List<ParsedDocumentChunk> chunks, int currentIndex) {
        StringBuilder context = new StringBuilder();
        if (currentIndex > 0) {
            context.append(chunks.get(currentIndex - 1).content());
        }
        if (currentIndex < chunks.size() - 1) {
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append(chunks.get(currentIndex + 1).content());
        }
        return context.isEmpty() ? "（无相邻文本）" : context.toString();
    }

    private <T> T parseJson(String json, Class<T> type) {
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("[Special Chunk][QA_ENRICH] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String formatEntityRegistry(Map<String, EntityRegistryEntry> registry) {
        if (registry.isEmpty()) {
            return "（无可用实体信息）";
        }
        return registry.values().stream()
                .map(e -> "- " + e.mention() + " → " + e.fullName()
                        + (e.definition() != null && !e.definition().isBlank()
                                ? "（" + e.definition() + "）" : ""))
                .collect(Collectors.joining("\n"));
    }
}
