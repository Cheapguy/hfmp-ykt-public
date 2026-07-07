package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("SYS_USER_ROLE")
public class SysUserRole {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long roleId;
}
