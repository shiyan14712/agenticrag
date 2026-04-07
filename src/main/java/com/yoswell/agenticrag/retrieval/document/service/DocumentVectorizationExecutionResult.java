package com.yoswell.agenticrag.retrieval.document.service;

public record DocumentVectorizationExecutionResult(boolean skipped, String detail) {

    public static DocumentVectorizationExecutionResult success(String detail) {
        return new DocumentVectorizationExecutionResult(false, detail);
    }

    public static DocumentVectorizationExecutionResult skipped(String detail) {
        return new DocumentVectorizationExecutionResult(true, detail);
    }
}
