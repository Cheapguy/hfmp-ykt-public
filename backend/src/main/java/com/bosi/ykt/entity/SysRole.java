package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("SYS_ROLE")
public class SysRole extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String roleCode;
    private String roleName;
    private String remark;
    private Integer status;
    /** 数据范围：ALL=全部 / COUNTY=本县 / OWN_ORG=本机构(乡镇)。县域隔离用，见 DataScopeResolver */
    private String dataScope;
}
