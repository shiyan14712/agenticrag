package com.yoswell.agenticrag.web.security.model;

import java.security.Principal;

/**
 * 租户用户身份实体领域模型
 * 在 Spring Security 的线程安全上下文中代表当前被认证的用户主体 (Principal)。
 * 包含了系统业务强依赖的核心字段：用户 ID、租户 ID 以及对应的角色信息。
 */
public class TenantUser implements Principal {
    
    /**
     * 系统用户唯一标识
     */
    private final String userId;

    /**
     * 租户唯一标识，用于在知识库与文档等资源中进行多租户数据物理/逻辑隔离
     */
    private final String tenantId;

    /**
     * 用户角色（如 ROLE_USER, ROLE_ADMIN），用于接口资源级别权限管控
     */
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

    /**
     * 框架默认的主体标识实现，这里返回用户 ID 作为唯一 Name
     */
    @Override
    public String getName() {
        return userId;
    }
}
