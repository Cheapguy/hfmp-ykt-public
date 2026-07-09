package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("SYS_USER")
public class SysUser extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orgId;
    private String username;
    private String password;
    private String realName;
    private String phone;
    private String email;
    private String idCard;
    /** SYS_ADMIN / TOWN_OP（乡镇经办）/ TOWN_AUDIT（乡镇审核）/ DEPT_OP（部门经办）/ DEPT_AUDIT（部门审核）*/
    private String userType;
    private Integer status;
    private LocalDateTime lastLoginAt;

    /** 角色 ID 列表：仅用于 create / update / detail 时与前端往返；不持久化到 SYS_USER 表 */
    @TableField(exist = false)
    private List<Long> roleIds;

    /** 列表页富化展示：所属机构名 / 角色名（顿号分隔），不持久化 */
    @TableField(exist = false)
    private String orgName;
    @TableField(exist = false)
    private String roleNames;
}
