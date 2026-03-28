package com.yoswell.agenticrag.platform.session.event;

public record SessionSwitchedEvent(String oldSessionId, String newSessionId, String userId) {}
