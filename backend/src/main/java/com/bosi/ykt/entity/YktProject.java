package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

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
    /** 追踪代码（上级资金的追踪码，多个项目可共用同一笔资金） */
    private String traceCode;
    /** 项目追踪名称：追踪代码对应的资金名称，如「困难群众救助补助资金」，V42 */
    private String trackProName;
    /** 是否自建项目：'是'/'否'。省级标准项目为否，各地自建为是，V42 */
    private String isSelfBuilt;
    /** 联系人（非必填，对齐生产表单，V37） */
    private String contactName;
    /** 联系方式（非必填） */
    private String contactPhone;
    /**
     * 所属行政区划码（9 位，如 990008000 辛县 / 990000000 省本级），V41。
     * 仅供列表展示与排查，<b>不是权限判据</b>——可见性以 PROJECT_CODE 前缀为准。
     */
    private String mofDivCode;
    /** 所属行政区划名称 */
    private String mofDivName;
    /** 补贴范围及对象 */
    private String subsidyScope;
    /** 政策文件名称 */
    private String policyDocName;
    /** 政策文号 */
    private String policyDocNo;
    /**
     * 政策文件附件（单份，旧字段）：/files/preview 下载地址；更旧的数据可能是纯文件名文本。
     * 已被 {@link #files} 多附件取代，保留只读回退——历史项目的地址还在这里，V35 已回迁一份到附件表。
     */
    private String policyFile;
    /**
     * 政策文件附件列表。不是 YKT_PROJECT 的列，随表单一起提交，保存项目后整体重写 YKT_PROJECT_FILE。
     * 之所以不做成即传即挂：新增项目时还没有 id，附件无处归属。
     */
    @TableField(exist = false)
    private List<YktProjectFile> files;
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
