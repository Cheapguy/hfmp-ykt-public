package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 项目额度（指标）挂接。按优先级使用指标 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_PROJECT_QUOTA")
public class YktProjectQuota extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    /** 挂接的指标 id（YKT_INDICATOR） */
    private Long indicatorId;
    /** 指标编码（快照指标文号） */
    private String indicatorCode;
    private String indicatorName;
    /** 使用优先级：1 优先扣减，用完顺延 */
    private Integer priority;
    /** 额度使用类型：PRIORITY 按优先级 / ASC 可用金额从小到大 / DESC 从大到小 */
    private String useRule;
    /** 指标可用额度 */
    private BigDecimal quotaAmount;
}
