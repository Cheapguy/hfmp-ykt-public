package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 省级处室字典（归口处室来源）。对应生产「省级处室信息」选择框 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_OFFICE")
public class YktOffice extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 处室编码：0100 厅领导 / 0101 综合处 … */
    private String officeCode;
    /** 处室名称 */
    private String officeName;
    /** 排序 */
    private Integer sortNo;
}
