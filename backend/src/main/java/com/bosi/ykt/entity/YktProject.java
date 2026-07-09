package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 补贴项目维护（主管部门）。手册 §七 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_PROJECT")
public class YktProject extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 项目编码：终审后自动生成 */
    private String projectCode;
    private String projectName;
    /** 项目简称：≤7 字 */
    private String shortName;
    /** 政策级次：CENTRAL/PROVINCE/CITY/COUNTY */
    private String policyLevel;
    /** 项目级次：PROV_SELF/PROV_CATALOG/CITY_SELF/COUNTY_SELF */
    private String projectLevel;
    /** 业务处室（归口处室） */
    private String deptName;
    /** 主管部门 */
    private String competentDept;
    /** 发放类型 */
    private String grantType;
    /** 预算来源 */
    private String budgetSource;
    /** 追踪代码 */
    private String traceCode;
    /** 补贴范围及对象 */
    private String subsidyScope;
    /** 政策文件名称 */
    private String policyDocName;
    /** 政策文号 */
    private String policyDocNo;
    /** 政策文件附件：/files/preview 下载地址（旧数据可能是纯文件名文本） */
    private String policyFile;
    /** 补贴标准 */
    private String subsidyStandard;
    /** 审核状态：DRAFT 草稿 / SUBMITTED 已送审 / APPROVED 终审 */
    private String auditStatus;
    /** 审核阶段（岗位）：ENTRY 录入岗 / SZ 市州财政综合岗 / DEPT 归口处室 / DONE 结束 */
    private String auditStage;
    /** 归口处室编码（市州综合岗审核时选定） */
    private String pivotOfficeCode;
    /** 归口处室名称 */
    private String pivotOfficeName;
    /** 状态显示文本（待审核 / 已送审 / 审核退回 / 已终审 …） */
    private String lastResult;
    /** 是否纳入项目库：0/1 */
    private Integer included;
    /** 挂接的中央目录清单编码 */
    private String catalogCode;
    /** 挂接的中央项目名称 */
    private String catalogName;
    private String remark;
}
