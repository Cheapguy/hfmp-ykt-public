package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("SYS_ORG")
public class SysOrg extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long parentId;
    private String orgCode;
    private String orgName;
    /** PROVINCE/CITY/COUNTY/TOWN（省/市/县/乡镇）+ DEPT（主管部门）*/
    private String orgType;
    private Integer sortNo;
    private Integer status;
}
