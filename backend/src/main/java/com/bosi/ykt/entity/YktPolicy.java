package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 政策基础录入（主管部门）。政策文号/标题/级次/年度/发文日期/结束日期/主管部门/简称/状态/内容(富文本) */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_POLICY")
public class YktPolicy extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 政策文号 */
    private String policyNo;
    /** 政策标题 */
    private String title;
    /** 政策信息级次：1中央 / 2省级 / 3市级 / 4县级 */
    private String policyLevel;
    /** 年度 */
    private String policyYear;
    /** 发文日期 */
    private String publishDate;
    /** 政策结束日期 */
    private String endDate;
    /** 政策(主管)部门 */
    private String competentDept;
    /** 简称 */
    private String shortName;
    /** 政策状态：1正常 / 2废止 */
    private String status;
    /** 政策内容（富文本 HTML） */
    private String content;
    private String remark;
}
