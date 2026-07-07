package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 机构/部门字典（从生产系统机构树同步）。供"主管部门/单位/乡镇"等下拉选择。 */
@Data
@TableName("YKT_AGENCY")
public class YktAgency {
    /** 机构唯一标识（生产 guid） */
    @TableId(value = "GUID", type = IdType.INPUT)
    private String guid;
    /** 机构编码 */
    private String code;
    /** 机构名称 */
    private String name;
    /** 父级 guid */
    private String superGuid;
    /** 层级：1 顶级 / 2 子级 */
    private Integer levelNo;
    /** 是否补贴单位：1 是（乡镇政府等）/ 0 否 */
    private String isSubsidy;
    /** 是否叶子 */
    private Integer isLeaf;
    /** 排序 */
    private Integer orderNum;
    /** 状态：1 正常 */
    private Integer status;
    /** 关联行政区划乡镇（SYS_ORG.id，仅乡镇政府 isSubsidy=1 有值），供村组级联 */
    private Long townId;
    /** 所属县市编码（990001 甲县 … 990008 辛县），供按县市过滤；admin 读全部 */
    private String countyCode;
}
