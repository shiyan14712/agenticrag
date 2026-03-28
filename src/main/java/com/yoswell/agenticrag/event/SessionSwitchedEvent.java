package com.yoswell.agenticrag.event;

public class SessionSwitchedEvent {
    private final String oldSessionId;
    private final String newSessionId;
    private final String userId;

    public SessionSwitchedEvent(String oldSessionId, String newSessionId, String userId) {
        this.oldSessionId = oldSessionId;
        this.newSessionId = newSessionId;
        this.userId = userId;
    }

    public String getOldSessionId() { return oldSessionId; }
    public String getNewSessionId() { return newSessionId; }
    public String getUserId() { return userId; }
}
