package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 发放数据审核 - 审核流水（流程进度）。对应生产「查看 → 流程进度」 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_AUDIT_LOG")
public class YktAuditLog extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    /** 序号 */
    private Integer seqNo;
    /** 已审岗 */
    private String doneStation;
    /** 操作人 */
    private String operator;
    /** 操作类型：新增 / 审核 / 取消 / 退回 */
    private String opType;
    /** 操作结果：送审 / 乡镇已审 / 部门经办已审 / 终审 / 取消审核 / 部门审核退回 … */
    private String opResult;
    /** 审核意见 */
    private String opinion;
    /** 操作时间 */
    private LocalDateTime opTime;
    /** 待审岗 */
    private String pendingStation;
}
