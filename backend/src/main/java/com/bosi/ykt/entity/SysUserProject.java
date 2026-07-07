package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户-项目数据权限（分配数据）。非空=该用户仅能看这些项目；空=按县码规则(本县自建+省级公有)。 */
@Data
@TableName("SYS_USER_PROJECT")
public class SysUserProject {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long projectId;
}
