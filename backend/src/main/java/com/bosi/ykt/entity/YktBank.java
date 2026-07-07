package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 开户银行设置维护（系统设置）。*/
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_BANK")
public class YktBank extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String bankCode;
    private String bankName;
    /** 联行号 */
    private String unionCode;
    /** 所属区划 */
    private String regionCode;
    private Integer status;
    private String remark;
}
