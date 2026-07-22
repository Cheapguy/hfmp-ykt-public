package com.bosi.ykt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bosi.ykt.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 补贴对象库（系统管理）。手册 §八。送审时按 姓名/身份证/社保卡/村组 校验 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("YKT_BENEFICIARY")
public class YktBeneficiary extends BaseEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 农户编码 */
    private String householdCode;
    /** 户主姓名 */
    private String headName;
    /** 户主身份证号 */
    private String headIdCard;
    /** 家庭成员姓名 */
    private String name;
    /** 家庭成员身份证号 */
    private String idCard;
    /** 社保卡卡号 */
    private String socialCard;
    /** 所属乡镇 */
    private Long townId;
    /** 村(居)委会 */
    private Long villageId;
    /** 村(居)民小组（一组/一社…） */
    private String groupName;
    /** 性别 */
    private String gender;
    /** 联系电话 */
    private String phone;
    /** 年龄 */
    private Integer age;
    /** 与户主关系（本人/儿子/父亲…） */
    private String relation;
    /** 开户银行 */
    private Long bankId;
    /** 账户名称 */
    private String accountName;
    /** 银行账号 */
    private String bankAccount;
    /** 公共账户名称 */
    private String publicAccountName;
    /** 公共账户账号 */
    private String publicAccountNo;
    /** 公共账户开户行 */
    private Long publicBankId;
    /** 是否低保户：0 否 / 1 是 */
    private String isDibao;
    /** 是否建档立卡户：0 否 / 1 是 */
    private String isFiling;
    /** 是否特困人员：0 否 / 1 是 */
    private String isTekun;
    /** 是否乡村人口：1 乡村 / 2 城镇 */
    private String isRural;
    /** 房屋情况 */
    private String houseInfo;
    /** 耕地情况 */
    private String farmlandInfo;
    /** 状态：0 作废 / 1 正常 / 2 停用 */
    private String status;
    /** 是否引用：0/1 */
    private Integer referred;
    private String remark;

    /** 创建人显示名（瞬态：createBy 用户 id 反查 realName，供列表展示，不落库） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String createByName;

    /** 原属辖区标签（瞬态：townId → 「县-乡镇」，供引用检索跨县回显，不落库） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String townLabel;
}
