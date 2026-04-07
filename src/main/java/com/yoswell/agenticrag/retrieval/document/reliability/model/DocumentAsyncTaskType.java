package com.yoswell.agenticrag.retrieval.document.reliability.model;

public enum DocumentAsyncTaskType {
    DOCUMENT_PARSE,
    DOCUMENT_VECTORIZATION,
    DOCUMENT_DELETE;

    public String value() {
        return name();
    }
}
