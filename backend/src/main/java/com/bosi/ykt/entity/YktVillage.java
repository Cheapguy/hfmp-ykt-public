package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 村组维护（系统管理）。手册 §六：村组编码及名称必须标准化 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_VILLAGE")
public class YktVillage extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 所属乡镇（SYS_ORG.id, orgType=TOWN） */
    private Long townId;
    private String villageCode;
    private String villageName;
    private Integer sortNo;
    private Integer status;
}
