package com.yoswell.agenticrag.retrieval.document.service;

import java.util.List;

import com.yoswell.agenticrag.core.agent.dto.RetrievedChunkDTO;

/**
 * 知识块检索服务接口
 *
 * <p>提供基于关键词（BM25）和向量（kNN）两种检索方式，
 * 每次检索均强制施加租户隔离与角色权限过滤，确保数据域安全性</p>
 */
public interface KnowledgeSearchService {

    /**
     * 通过关键词执行 BM25 全文检索
     *
     * @param query        查询文本
     * @param tenantId     当前租户 ID
     * @param allowedRoles 调用方允许访问的角色列表
     * @param size         最大返回数量
     * @return 检索结果
     */
    List<RetrievedChunkDTO> searchByKeyword(String query, String tenantId, List<String> allowedRoles, int size);

    /**
     * 通过稠密向量执行 kNN 相似度检索
     *
     * @param queryVector  查询向量
     * @param tenantId     当前租户 ID
     * @param allowedRoles 调用方允许访问的角色列表
     * @param size         最大返回数量
     * @return 检索结果
     */
    List<RetrievedChunkDTO> searchByVector(List<Float> queryVector, String tenantId, List<String> allowedRoles, int size);
}
