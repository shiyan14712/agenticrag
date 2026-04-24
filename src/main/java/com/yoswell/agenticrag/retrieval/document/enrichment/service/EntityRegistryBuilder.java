package com.yoswell.agenticrag.retrieval.document.enrichment.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoswell.agenticrag.retrieval.document.enrichment.entity.EntityRegistryDO;
import com.yoswell.agenticrag.retrieval.document.mapper.EntityRegistryMapper;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.EntityRegistryEntry;
import com.yoswell.agenticrag.retrieval.document.enrichment.model.NerResult;
import com.yoswell.agenticrag.retrieval.document.parser.model.ParsedDocumentChunk;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;

@Service
public class EntityRegistryBuilder {

    private static final Logger log = LoggerFactory.getLogger(EntityRegistryBuilder.class);

    private static final String NER_SYSTEM_PROMPT = """
            你是一个专业的命名实体识别（NER）助手。请从给定文本中提取所有命名实体、缩写、专有名词。
            对每个实体，给出：
            - mention: 原文中的表述
            - fullName: 完整名称（如果原文已经是完整名称，则与 mention 相同）
            - definition: 简要定义或描述（一句话）
            - category: 实体类别，必须是以下之一：PERSON, ORGANIZATION, LOCATION, ABBREVIATION, TERM, OTHER

            如果文本中没有可提取的实体，返回 entities 为空数组。
            """;

    private static final ResponseFormat NER_RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("NerResult")
                    .rootElement(JsonObjectSchema.builder()
                            .addProperty("entities", JsonArraySchema.builder()
                                    .items(JsonObjectSchema.builder()
                                            .addProperty("mention", new JsonStringSchema())
                                            .addProperty("fullName", new JsonStringSchema())
                                            .addProperty("definition", new JsonStringSchema())
                                            .addProperty("category", JsonEnumSchema.builder()
                                                    .enumValues("PERSON", "ORGANIZATION", "LOCATION",
                                                            "ABBREVIATION", "TERM", "OTHER")
                                                    .build())
                                            .required(List.of("mention", "fullName", "definition", "category"))
                                            .build())
                                    .build())
                            .required(List.of("entities"))
                            .build())
                    .build())
            .build();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final EntityRegistryMapper entityRegistryMapper;

    public EntityRegistryBuilder(ChatModel chatModel, EntityRegistryMapper entityRegistryMapper) {
        this.chatModel = chatModel;
        this.entityRegistryMapper = entityRegistryMapper;
    }

    /**
     * 从已切分的 chunks 中构建 Entity Registry 并持久化
     */
    public Map<String, EntityRegistryEntry> buildAndPersist(
            List<ParsedDocumentChunk> chunks, String documentId, String tenantId) {

        log.info("[Special Chunk][NER] 开始构建 Entity Registry: documentId={}, chunkCount={}",
                documentId, chunks.size());

        Map<String, EntityRegistryEntry> registry = new LinkedHashMap<>();

        for (int i = 0; i < chunks.size(); i++) {
            ParsedDocumentChunk chunk = chunks.get(i);
            try {
                NerResult nerResult = extractEntities(chunk.content());
                if (nerResult != null && nerResult.entities() != null) {
                    for (NerResult.Entity entity : nerResult.entities()) {
                        String key = entity.mention().toLowerCase().strip();
                        registry.merge(key,
                                new EntityRegistryEntry(entity.mention(), entity.fullName(),
                                        entity.definition(), entity.category()),
                                (existing, incoming) -> existing.fullName().length() >= incoming.fullName().length()
                                        ? existing : incoming);
                    }
                }
                if ((i + 1) % 10 == 0 || i + 1 == chunks.size()) {
                    log.info("[Special Chunk][NER] 进度: documentId={}, {}/{}, registrySize={}",
                            documentId, i + 1, chunks.size(), registry.size());
                }
            } catch (Exception e) {
                log.warn("[Special Chunk][NER] chunk NER 失败，跳过: documentId={}, chunkIndex={}",
                        documentId, chunk.chunkIndex(), e);
            }
        }

        persistRegistry(registry, documentId, tenantId);

        log.info("[Special Chunk][NER] Entity Registry 构建完成: documentId={}, entityCount={}",
                documentId, registry.size());
        return registry;
    }

    /**
     * 从数据库加载已有的 Entity Registry
     */
    public Map<String, EntityRegistryEntry> loadFromDb(String documentId) {
        List<EntityRegistryDO> records = entityRegistryMapper.selectList(
                new LambdaQueryWrapper<EntityRegistryDO>()
                        .eq(EntityRegistryDO::getDocumentId, documentId));

        Map<String, EntityRegistryEntry> registry = new LinkedHashMap<>();
        for (EntityRegistryDO record : records) {
            registry.put(record.getMention().toLowerCase().strip(),
                    new EntityRegistryEntry(record.getMention(), record.getFullName(),
                            record.getDefinition(), record.getCategory()));
        }
        return registry;
    }

    private NerResult extractEntities(String text) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(NER_SYSTEM_PROMPT),
                        UserMessage.from(text)))
                .responseFormat(NER_RESPONSE_FORMAT)
                .build();

        ChatResponse response = chatModel.chat(request);
        String json = response.aiMessage().text();
        return parseNerResult(json);
    }

    private NerResult parseNerResult(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, NerResult.class);
        } catch (Exception e) {
            log.warn("[Special Chunk][NER] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private void persistRegistry(Map<String, EntityRegistryEntry> registry, String documentId, String tenantId) {
        entityRegistryMapper.delete(
                new LambdaQueryWrapper<EntityRegistryDO>()
                        .eq(EntityRegistryDO::getDocumentId, documentId));

        List<EntityRegistryDO> records = new ArrayList<>(registry.size());
        for (EntityRegistryEntry entry : registry.values()) {
            EntityRegistryDO record = new EntityRegistryDO();
            record.setDocumentId(documentId);
            record.setTenantId(tenantId);
            record.setMention(entry.mention());
            record.setFullName(entry.fullName());
            record.setDefinition(entry.definition());
            record.setCategory(entry.category());
            records.add(record);
        }

        for (EntityRegistryDO record : records) {
            entityRegistryMapper.insert(record);
        }

        log.info("[Special Chunk][NER] Entity Registry 已持久化: documentId={}, count={}",
                documentId, records.size());
    }
}
