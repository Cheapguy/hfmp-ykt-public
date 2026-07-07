package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 花名册明细（乡镇填报 → 多级审核）。*/
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_ROSTER")
public class YktRoster extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    /** 引用的补贴对象 */
    private Long beneficiaryId;
    /** 冗余快照，送审时与补贴对象库校验 */
    private String name;
    private String idCard;
    private String socialCard;
    private Long villageId;
    /** 补贴标准 / 补贴金额 */
    private BigDecimal standard;
    private BigDecimal amount;
    /**
     * 审核状态：
     * DRAFT 待编制 / TOWN_SUBMIT 乡镇送审 / TOWN_AUDIT 乡镇审核通过 /
     * DEPT_SUBMIT 部门送审 / FINAL 终审
     */
    private String auditStatus;
    /**
     * 支付状态：
     * NONE 未生成 / APPLIED 已生成支付申请 / PAID 已支付 / REFUNDED 已退款 / RETURNED 已退回
     */
    private String payStatus;
}
