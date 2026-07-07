package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("SYS_MENU")
public class SysMenu {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long parentId;
    private String menuCode;
    private String menuName;
    /** M=目录 C=菜单 F=按钮 */
    private String menuType;
    private String path;
    private String component;
    private String icon;
    private Integer sortNo;
    private String permission;
    private Integer visible;
}
