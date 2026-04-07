package com.yoswell.agenticrag.retrieval.document.reliability.model;

public enum MqConsumeStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    public String value() {
        return name();
    }
}
