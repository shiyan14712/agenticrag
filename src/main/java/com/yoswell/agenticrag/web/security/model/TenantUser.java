package com.yoswell.agenticrag.web.security.model;

import java.security.Principal;

public class TenantUser implements Principal {
    private final String userId;
    private final String tenantId;
    private final String role;

    public TenantUser(String userId, String tenantId, String role) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getRole() {
        return role;
    }

    @Override
    public String getName() {
        return userId;
    }
}
