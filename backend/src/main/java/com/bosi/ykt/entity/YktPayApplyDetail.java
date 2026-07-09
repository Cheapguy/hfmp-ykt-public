package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 支付申请-指标扣减明细。手册 §十三：发起支付按使用规则逐指标预扣减额度，撤销时据此回滚 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_PAY_APPLY_DETAIL")
public class YktPayApplyDetail extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applyId;
    private Long indicatorId;
    private String indicatorNo;
    /** 本次预扣减金额 */
    private BigDecimal deductAmount;
}
