package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 国标「51项」补贴项目目录（源=生产项目字典 2 开头国标码）。财政端 51项补贴项目发放表 数据口径。 */
@Data
@TableName("YKT_STD_ITEM")
public class YktStdItem {
    @TableId(type = IdType.INPUT)
    private String code;
    private String itemName;
    private String shortName;
    private Integer sortNo;
}
