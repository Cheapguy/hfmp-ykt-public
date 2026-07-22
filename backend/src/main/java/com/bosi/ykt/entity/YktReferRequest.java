package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 引用请求（人户迁移跨乡镇引用的审批单）。手册 §八-6 扩展。
 * 乡镇 A 引用乡镇 B 名下补贴对象 → 生成 PENDING 请求；B 在工作台审核，
 * 通过(APPROVED)才按 PAYLOAD_JSON 复制建档(referred=1)，驳回(REJECTED)则作废。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_REFER_REQUEST")
public class YktReferRequest extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 被引用人身份证号（快照） */
    private String idCard;
    /** 被引用人姓名（快照） */
    private String name;
    /** 户主姓名（快照） */
    private String headName;
    /** 户主身份证号（快照） */
    private String headIdCard;
    /** 原始建档 id（被引用对象） */
    private Long sourceId;
    /** 被引用乡镇 id = 审批方 */
    private Long sourceTownId;
    /** 引用方乡镇 id */
    private Long targetTownId;
    /** 引用方选定村(居)委会 id */
    private Long targetVillageId;
    /** 引用方村(居)民小组 */
    private String targetGroupName;
    /** 复制建档载荷（引用方填好的整条 YktBeneficiary，JSON） */
    private String payloadJson;
    /** 留言（必填） */
    private String message;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    /** 申请人 uid */
    private Long applyUserId;
    /** 申请人姓名 */
    private String applyUserName;
    /** 申请方辖区展示（引用方乡镇 label） */
    private String applyTownLabel;
    /** 被引用方辖区展示（原乡镇 label） */
    private String sourceTownLabel;
    /** 申请时间 */
    private java.time.LocalDateTime applyTime;
    /** 审批人 uid */
    private Long auditUserId;
    /** 审批时间 */
    private java.time.LocalDateTime auditTime;
    /** 审批意见 / 驳回原因 */
    private String auditRemark;
    /** 通过后生成的副本 id */
    private Long resultBeneficiaryId;
}
