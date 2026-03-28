package com.yoswell.agenticrag.platform.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 系统用户实体，对应数据库表 sys_user。
 * 当前业务采用一企业一账号模型，userId 可视作企业账号唯一标识。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 账号业务唯一标识（单租户模式下，逻辑上等同于 tenant_id）
     */
    private String userId;

    /** 登录用户名。 */
    private String username;

    /** 密码哈希值。 */
    private String password;

    /** 角色集合字符串（逗号分隔）。 */
    private String roles;

    /** 用户状态，如 ACTIVE 或 DISABLED。 */
    private String status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
