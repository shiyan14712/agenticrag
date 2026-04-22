package com.yoswell.agenticrag.platform.user.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yoswell.agenticrag.platform.user.entity.SysUser;

/**
 * 用户数据访问接口。
 * 继承 MyBatis-Plus BaseMapper，提供 sys_user 的基础 CRUD 能力。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
