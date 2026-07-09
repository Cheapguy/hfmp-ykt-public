package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 预算指标库（预算管理一体化）。手册 §十二：只有热点分类含"一卡通"的指标可挂接 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_INDICATOR")
public class YktIndicator extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 指标文号 */
    private String indicatorNo;
    /** 政府经济分类 */
    private String govEconCode;
    private String govEconName;
    /** 部门经济分类 */
    private String deptEconCode;
    private String deptEconName;
    /** 预算项目 */
    private String budgetProject;
    /** 资金性质 */
    private String fundNature;
    /** 指标下达数 */
    private BigDecimal issuedAmount;
    /** 指标冻结数 */
    private BigDecimal frozenAmount;
    /** 已支付数 */
    private BigDecimal paidAmount;
    /** 可支付余额 */
    private BigDecimal availableAmount;
    /** 预算单位 */
    private String budgetUnit;
    /** 指标说明 */
    private String indicatorDesc;
    /** 热点分类（"一卡通"标识的才可挂接） */
    private String hotClassName;
}
