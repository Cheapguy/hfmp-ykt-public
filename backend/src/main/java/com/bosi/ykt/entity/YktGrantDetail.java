package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 花名册明细（发放明细）。来源：清册明细 + 银行信息 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_GRANT_DETAIL")
public class YktGrantDetail extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long batchId;
    private String batchCode;
    /** 排序序号 */
    private Integer sortNo;
    /** 支付状态：已支付 / 已申请 */
    private String payStatus;
    /** 户主姓名 / 身份证号 */
    private String holderName;
    private String holderIdCard;
    /** 收款人姓名 / 身份证号 */
    private String payeeName;
    private String payeeIdCard;
    /** 银行账号 / 开户银行 */
    private String bankAccount;
    private String bankName;
    /** 村(居)委会 / 民小组 */
    private String villageName;
    private String groupName;
    /** 享受人姓名 / 身份证号 */
    private String beneficiaryName;
    private String beneficiaryIdCard;
    private String phone;
    /** 现居住地 */
    private String residence;
    private Integer age;
    /** 补贴月标准 / 补贴金额 */
    private BigDecimal standard;
    private BigDecimal amount;
    /** 填报日期 */
    private LocalDate fillDate;
    private String relationship;
    private String remark;
    /** 支付失败原因（银行代发失败时回填：卡号已注销 / 卡已达限额 / 卡号与户名不一致 等） */
    private String failReason;
    /** 发放次数：0=首发，重构再发 +1（体现二次/三次发放） */
    private Integer retryTimes;
    /** 停发原因（编制阶段对明细停发时必填；停发明细不发起支付，银行环节归退款） */
    private String stopReason;
}
