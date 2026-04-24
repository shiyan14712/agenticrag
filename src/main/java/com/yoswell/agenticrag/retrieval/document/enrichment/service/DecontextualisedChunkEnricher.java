package com.yoswell.agenticrag.retrieval.document.enrichment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.yoswell.agenticrag.retrieval.document.enrichment.model.EnrichedChunk;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.EntityRegistryEntry;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

@Service
public class DecontextualisedChunkEnricher {

    private static final Logger log = LoggerFactory.getLogger(DecontextualisedChunkEnricher.class);

    private static final String DECONTEXT_SYSTEM_PROMPT = """
            你是一个专业的文本改写助手。你的任务是对给定的文本片段进行"去上下文化"改写，使其脱离原文也能被完全理解。

            你需要做的事：
            - 把代词（他、她、它、该、其、这、那）替换为具体指代对象
            - 把缩写、简称展开为全称（参考提供的实体注册表）
            - 补全省略的主语、地点、时间等关键信息
            - 解析桥接回指（如"政府"→"印度政府"）

            你不能做的事：
            - 不要改变原文的事实内容
            - 不要添加原文和实体注册表中都没有的信息
            - 不要改变原文的语气和风格
            - 不要做摘要或精简，保留原文的完整信息量
            - 如果文本本身已经自包含、无需改写，请原样返回

            直接返回改写后的文本，不要添加任何解释或标记。
            """;

    private final ChatModel chatModel;

    public DecontextualisedChunkEnricher(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public List<EnrichedChunk> enrich(List<ParsedDocumentChunk> chunks,
                                      Map<String, EntityRegistryEntry> entityRegistry,
                                      String documentId) {

        log.info("[Special Chunk][DECONTEXT] 开始去上下文化改写: documentId={}, chunkCount={}",
                documentId, chunks.size());

        String registryContext = formatEntityRegistry(entityRegistry);
        List<EnrichedChunk> enrichedChunks = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            ParsedDocumentChunk chunk = chunks.get(i);
            try {
                String rewritten = decontextualise(chunk.content(), registryContext);
                enrichedChunks.add(new EnrichedChunk(
                        chunk.chunkId(), chunk.chunkIndex(),
                        chunk.content(), rewritten));

                if ((i + 1) % 5 == 0 || i + 1 == chunks.size()) {
                    log.info("[Special Chunk][DECONTEXT] 进度: documentId={}, {}/{}",
                            documentId, i + 1, chunks.size());
                }
            } catch (Exception e) {
                log.warn("[Special Chunk][DECONTEXT] 改写失败，保留原文: documentId={}, chunkIndex={}",
                        documentId, chunk.chunkIndex(), e);
                enrichedChunks.add(new EnrichedChunk(
                        chunk.chunkId(), chunk.chunkIndex(),
                        chunk.content(), chunk.content()));
            }
        }

        log.info("[Special Chunk][DECONTEXT] 去上下文化改写完成: documentId={}, chunkCount={}",
                documentId, enrichedChunks.size());
        return enrichedChunks;
    }

    private String decontextualise(String chunkContent, String registryContext) {
        String userPrompt = "## 实体注册表\n" + registryContext + "\n\n## 待改写文本\n" + chunkContent;

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(DECONTEXT_SYSTEM_PROMPT),
                        UserMessage.from(userPrompt)))
                .build();

        ChatResponse response = chatModel.chat(request);
        return response.aiMessage().text().strip();
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
