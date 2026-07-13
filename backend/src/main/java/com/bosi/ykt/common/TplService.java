package com.bosi.ykt.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.entity.YktTplItem;
import com.bosi.ykt.mapper.YktTplItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发放表定义解析：项目有自定义列则用自定义，否则回落内置默认 18 列（与生产 99999.xls 模板一致）。
 * 供 模板定义 CRUD 与 清册模板导出/导入校验 共用。
 */
@Service
@RequiredArgsConstructor
public class TplService {

    private final YktTplItemMapper tplItemMapper;

    /** 可绑定的明细字段：属性名 -> [中文名, 强制数据类型]（绑定后类型不可另选，对齐生产 setTypeDefault） */
    public static final Map<String, String[]> BIND_FIELDS = new LinkedHashMap<>();
    static {
        BIND_FIELDS.put("sortNo", new String[]{"排序序号", "int"});
        BIND_FIELDS.put("holderName", new String[]{"户主姓名", "text"});
        BIND_FIELDS.put("holderIdCard", new String[]{"户主身份证号", "idcard"});
        BIND_FIELDS.put("payeeName", new String[]{"收款人姓名", "text"});
        BIND_FIELDS.put("payeeIdCard", new String[]{"收款人身份证号", "idcard"});
        BIND_FIELDS.put("bankAccount", new String[]{"银行账号", "text"});
        BIND_FIELDS.put("bankName", new String[]{"开户银行（校验系统银行设置）", "text"});
        BIND_FIELDS.put("villageName", new String[]{"村(居)委会（校验村组维护）", "text"});
        BIND_FIELDS.put("groupName", new String[]{"村(居)民小组", "text"});
        BIND_FIELDS.put("beneficiaryName", new String[]{"享受人", "text"});
        BIND_FIELDS.put("beneficiaryIdCard", new String[]{"享受人身份证号", "idcard"});
        BIND_FIELDS.put("phone", new String[]{"联系电话", "text"});
        BIND_FIELDS.put("standard", new String[]{"补贴标准", "decimal"});
        BIND_FIELDS.put("amount", new String[]{"补贴金额", "decimal"});
        BIND_FIELDS.put("fillDate", new String[]{"填报日期", "date"});
        BIND_FIELDS.put("remark", new String[]{"备注", "text"});
    }

    /** 绑定字段在清册网格的大列组（sortNo/remark 不在组里：网格有固定位置） */
    public static final Map<String, String> BIND_GROUPS = new LinkedHashMap<>();
    static {
        BIND_GROUPS.put("holderName", "HOLDER");
        BIND_GROUPS.put("holderIdCard", "HOLDER");
        BIND_GROUPS.put("payeeName", "PAYEE");
        BIND_GROUPS.put("payeeIdCard", "PAYEE");
        BIND_GROUPS.put("bankAccount", "PAYEE");
        BIND_GROUPS.put("bankName", "PAYEE");
        BIND_GROUPS.put("villageName", "BENEFICIARY");
        BIND_GROUPS.put("groupName", "BENEFICIARY");
        BIND_GROUPS.put("beneficiaryName", "BENEFICIARY");
        BIND_GROUPS.put("beneficiaryIdCard", "BENEFICIARY");
        BIND_GROUPS.put("phone", "BENEFICIARY");
        BIND_GROUPS.put("standard", "GRANT");
        BIND_GROUPS.put("amount", "GRANT");
        BIND_GROUPS.put("fillDate", "GRANT");
    }

    /** 内置默认 18 列（每次新建实例，防调用方改坏共享状态） */
    public static List<YktTplItem> defaults() {
        List<YktTplItem> l = new ArrayList<>();
        l.add(col("sortNo", "排序序号", "int", 0, "sortNo", 0));
        l.add(col("holderName", "户主姓名", "text", 1, "holderName", 1));
        l.add(col("holderIdCard", "户主身份证号", "idcard", 1, "holderIdCard", 1));
        l.add(col("payeeName", "收款人姓名", "text", 1, "payeeName", 1));
        l.add(col("payeeIdCard", "收款人身份证号", "idcard", 1, "payeeIdCard", 1));
        l.add(col("bankAccount", "银行账号", "text", 1, "bankAccount", 1));
        l.add(col("bankName", "开户银行", "text", 1, "bankName", 1));
        l.add(col("villageName", "村(居)委会", "text", 1, "villageName", 1));
        l.add(col("groupName", "村(居)民小组", "text", 1, "groupName", 1));
        l.add(col("beneficiaryName", "享受人", "text", 1, "beneficiaryName", 1));
        l.add(col("beneficiaryIdCard", "享受人身份证号", "idcard", 1, "beneficiaryIdCard", 1));
        l.add(col("phone", "联系电话", "text", 0, "phone", 0));
        l.add(col("workPlace", "务工地点", "text", 0, null, 0));
        l.add(col("transport", "乘坐交通工具", "text", 0, null, 0));
        l.add(col("standard", "补贴标准", "decimal", 0, "standard", 0));
        l.add(col("amount", "补贴金额", "decimal", 1, "amount", 1));
        l.add(col("fillDate", "填报日期", "date", 0, "fillDate", 0));
        l.add(col("remark", "备注", "text", 0, "remark", 0));
        for (int i = 0; i < l.size(); i++) l.get(i).setSortNo(i + 1);
        return l;
    }

    private static YktTplItem col(String key, String label, String type, int required, String bind, int fixed) {
        YktTplItem t = new YktTplItem();
        t.setItemKey(key);
        t.setItemLabel(label);
        t.setColType(type);
        t.setRequiredFlag(required);
        t.setBindField(bind);
        t.setFixedFlag(fixed);
        if (bind == null) t.setColGroup("EXT");   // 自由列缺省进扩展信息组
        return t;
    }

    /** 项目自定义列（按排序），无则空列表 */
    public List<YktTplItem> customOf(Long projectId) {
        return tplItemMapper.selectList(new QueryWrapper<YktTplItem>()
                .eq("PROJECT_ID", projectId).orderByAsc("SORT_NO", "ID"));
    }

    /** 项目生效列：自定义优先，无自定义回落默认 */
    public List<YktTplItem> columnsOf(Long projectId) {
        List<YktTplItem> custom = customOf(projectId);
        return custom.isEmpty() ? defaults() : custom;
    }
}
