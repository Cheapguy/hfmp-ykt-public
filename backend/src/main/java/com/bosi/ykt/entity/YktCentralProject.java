package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 中央补贴项目清单（挂接目标）。对应生产「补贴项目挂接 → 中央补贴项目清单」 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_CENTRAL_PROJECT")
public class YktCentralProject extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 中央项目编码 */
    private String projectCode;
    /** 中央项目名称 */
    private String projectName;
    /** 分类：必纳项目 / 可纳项目 */
    private String category;
    /** 主管部门 */
    private String competentDept;
    /** 政策依据 */
    private String policyBasis;
}
