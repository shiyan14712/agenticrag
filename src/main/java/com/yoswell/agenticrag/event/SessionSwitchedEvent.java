package com.yoswell.agenticrag.event;

public record SessionSwitchedEvent(String oldSessionId, String newSessionId, String userId) {}
