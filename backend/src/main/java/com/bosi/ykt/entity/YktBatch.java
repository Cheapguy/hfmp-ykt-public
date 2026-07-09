package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 补贴批次维护（主管部门）。手册 §十、§十一 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_BATCH")
public class YktBatch extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 批次编码（下达后生成） */
    private String batchCode;
    private String batchName;
    /** 发放资金标题 / 批次标题 */
    private String fundTitle;
    /** 截止日期（YYYY-MM-DD） */
    private String deadline;
    private Long projectId;
    /** 下达乡镇 */
    private Long townId;
    /** 当前待审岗：TOWN_SUBMIT 乡镇录入 / TOWN_REVIEW 乡镇复核岗 / TOWN_AUDIT 乡镇审核
     *  / DEPT_OP 部门经办岗初审 / DEPT_REVIEW 部门复核岗 / DONE 批次发送(结束) */
    private String auditStage;
    /** 状态列显示文本（待乡镇审核 / 乡镇退回 / 部门审核退回 / 已终审 …） */
    private String lastResult;
    /** 发放时间 */
    private java.time.LocalDateTime grantTime;
    /**
     * 批次状态：
     * NEW 未下达 / ISSUED 已下达 / SENT 已发送一体化 / PAID 已支付 / PAID_OUT 已发放 /
     * PART_REFUND 部分退款 / PART_RETURN 部分退回
     */
    private String status;
    /** 计划金额 / 计划人数 */
    private BigDecimal planAmount;
    private Integer planCount;
    /** 实际发放金额 / 实际发放人数 */
    private BigDecimal actualAmount;
    private Integer actualCount;
    /** 退款金额 */
    private BigDecimal refundAmount;
    /** 退回金额 */
    private BigDecimal returnAmount;
    /** 停发金额 */
    private BigDecimal stopAmount;
    private String remark;
}
