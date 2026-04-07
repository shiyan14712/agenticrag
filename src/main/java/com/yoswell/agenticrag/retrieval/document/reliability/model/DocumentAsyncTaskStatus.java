package com.yoswell.agenticrag.retrieval.document.reliability.model;

public enum DocumentAsyncTaskStatus {
    PENDING,
    DISPATCHED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    public String value() {
        return name();
    }
}
