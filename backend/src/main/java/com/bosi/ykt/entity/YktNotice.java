package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 通知公告管理（主管部门上传 / 乡镇下载）。手册 §九 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_NOTICE")
public class YktNotice extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String title;
    private String fileUrl;
    private String fileName;
    /** 下发单位（乡镇 SYS_ORG.id，逗号分隔；空/未下发=无人可见，乡镇端按本机构 id 匹配） */
    private String targetOrgIds;
    private Integer status;
}
