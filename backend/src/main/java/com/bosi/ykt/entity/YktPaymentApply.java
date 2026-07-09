package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 支付申请（集中支付）。手册 §十三、§十四 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_PAYMENT_APPLY")
public class YktPaymentApply extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String applyNo;
    private Long batchId;
    private Long projectId;
    private BigDecimal amount;
    /** 资金往来对象 */
    private String payee;
    /** 用途 */
    private String usage;
    /** PENDING_SUBMIT 待送审 / SUBMITTED 已送审 / PAID 已支付 / REVOKED 已撤销 */
    private String status;
}
