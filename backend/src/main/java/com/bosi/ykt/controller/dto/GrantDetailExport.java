package com.bosi.ykt.controller.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/** 花名册导出行（EasyExcel） */
@Data
@ColumnWidth(16)
public class GrantDetailExport {
    @ExcelProperty("序号")
    private Integer sortNo;
    @ExcelProperty("支付状态")
    private String payStatus;
    @ExcelProperty("户主姓名")
    private String holderName;
    @ExcelProperty("户主身份证号")
    @ColumnWidth(22)
    private String holderIdCard;
    @ExcelProperty("收款人姓名")
    private String payeeName;
    @ExcelProperty("收款人身份证号")
    @ColumnWidth(22)
    private String payeeIdCard;
    @ExcelProperty("银行账号")
    @ColumnWidth(24)
    private String bankAccount;
    @ExcelProperty("开户银行")
    @ColumnWidth(28)
    private String bankName;
    @ExcelProperty("村(居)委会")
    private String villageName;
    @ExcelProperty("村(居)民小组")
    private String groupName;
    @ExcelProperty("享受人")
    private String beneficiaryName;
    @ExcelProperty("享受人身份证号")
    @ColumnWidth(22)
    private String beneficiaryIdCard;
    @ExcelProperty("联系电话")
    private String phone;
    @ExcelProperty("现居住地")
    private String residence;
    @ExcelProperty("年龄")
    @ColumnWidth(8)
    private Integer age;
    @ExcelProperty("补贴金额")
    private java.math.BigDecimal amount;
    @ExcelProperty("填报日期")
    private String fillDate;
}
