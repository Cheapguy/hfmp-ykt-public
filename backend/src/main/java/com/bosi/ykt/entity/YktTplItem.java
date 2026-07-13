package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发放表定义：项目级清册导入模板列。对生产「清册样式定义→数据项」的裁剪版（每项目一套列）。
 * 刻意不继承 BaseEntity：列定义走物理删除，配 (PROJECT_ID,ITEM_KEY) 唯一索引可反复增删。
 */
@Data
@TableName("YKT_TPL_ITEM")
public class YktTplItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    /** 标识（存储字段名，小写英文开头，英数下划线 ≤20） */
    private String itemKey;
    /** 列名（Excel 表头） */
    private String itemLabel;
    /** 数据类型：text / int / decimal / date / idcard / enum */
    private String colType;
    /** 枚举允许值（colType=enum 时必填，逗号分隔，如 火车,汽车,飞机） */
    private String enumValues;
    /** 计算公式（对生产清册样式公式，如 standard*months）：仅数值列可设，导入时按行计算、忽略单元格内容 */
    private String formula;
    /** 1=导入时必填 */
    private Integer requiredFlag;
    /** 绑定的明细字段（YktGrantDetail 属性名，空=自由列存 EXT_JSON） */
    private String bindField;
    /** 1=固定列（送审/发放链路依赖），不可删、不可改标识/类型/绑定 */
    private Integer fixedFlag;
    /** 自由列清册显示分组：HOLDER/PAYEE/BENEFICIARY/GRANT/EXT（绑定列跟随系统字段所在组，此值为 null） */
    private String colGroup;
    private Integer sortNo;
    private String remark;
    private Long tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long createBy;
}
