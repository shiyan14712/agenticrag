package com.yoswell.agenticrag.event;

public class SessionCreatedEvent {
    private final String sessionId;
    private final String userId;

    public SessionCreatedEvent(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
}
