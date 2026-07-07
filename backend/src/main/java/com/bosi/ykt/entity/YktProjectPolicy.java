package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目 ↔ 政策 关联（政策关联项目）。
 * 刻意不继承 BaseEntity（无 @TableLogic DELETED）：取消关联走物理删除，
 * 配合 (PROJECT_ID,POLICY_ID) 唯一索引可反复增删，不会被软删残留撑爆唯一约束。
 */
@Data
@TableName("YKT_PROJECT_POLICY")
public class YktProjectPolicy {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private Long policyId;
    private Long tenantId;
    private LocalDateTime createTime;
    private Long createBy;
}
