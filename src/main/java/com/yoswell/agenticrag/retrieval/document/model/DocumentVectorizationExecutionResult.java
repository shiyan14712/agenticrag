package com.yoswell.agenticrag.retrieval.document.model;

/**
 * 文档向量化执行结果
 */
public record DocumentVectorizationExecutionResult(boolean skipped, String detail) {

    public static DocumentVectorizationExecutionResult success(String detail) {
        return new DocumentVectorizationExecutionResult(false, detail);
    }

    public static DocumentVectorizationExecutionResult skipped(String detail) {
        return new DocumentVectorizationExecutionResult(true, detail);
    }
}