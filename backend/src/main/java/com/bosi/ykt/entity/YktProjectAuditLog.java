package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 补贴项目审核流水（流程进度）。对应生产「补贴项目审核 → 审核历史 → 查看」 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_PROJECT_AUDIT_LOG")
public class YktProjectAuditLog extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    /** 序号 */
    private Integer seqNo;
    /** 已审岗 */
    private String doneStation;
    /** 操作人 */
    private String operator;
    /** 操作类型：送审 / 审核 / 退回 */
    private String opType;
    /** 操作结果：已送审 / 审核通过 / 审核退回 … */
    private String opResult;
    /** 审核意见 */
    private String opinion;
    /** 操作时间 */
    private LocalDateTime opTime;
    /** 待审岗 */
    private String pendingStation;
}
