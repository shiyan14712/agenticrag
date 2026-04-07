package com.yoswell.agenticrag.retrieval.document.reliability.model;

public enum MessageOutboxStatus {
    PENDING,
    DISPATCHING,
    SENT,
    FAILED;

    public String value() {
        return name();
    }
}
