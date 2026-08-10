package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 业务处室（财政归口处室）字典。补贴项目「业务处室」下拉数据源，21 条内设科室（migrate V34）。
 *
 * <p>与 {@link YktOffice} 的区别：那张是<b>省财政厅</b>处室（厅领导/综合处/税政处…），
 * 原供市州财政综合岗选定归口处室；本张是<b>财政局</b>内设科室（局领导/预算科/国库科…）。
 * 审核链简化后综合岗退场，归口处室改由录入时填的业务处室直接充当。
 *
 * <p>不继承 BaseEntity：纯静态字典，没有租户/软删/审计列的需要。
 */
@Data
@TableName("YKT_BIZ_OFFICE")
public class YktBizOffice {
    @TableId(type = IdType.INPUT)
    private Long id;
    /** 处室编码：0001 局领导 / 0002 预算科 … 0021 国库中心 */
    private String officeCode;
    private String officeName;
    private Integer sortNo;
    /** 1 启用 / 0 停用 */
    private Integer status;
}
