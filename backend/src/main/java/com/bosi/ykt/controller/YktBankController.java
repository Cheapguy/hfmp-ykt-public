package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktBank;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.mapper.YktBankMapper;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 开户银行设置维护。手册 §五 */
@RestController
@RequestMapping("/setup/bank")
@RequiredArgsConstructor
public class YktBankController extends BaseCrudController<YktBankMapper, YktBank> {
    private final YktBankMapper mapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    @Override protected YktBankMapper getMapper() { return mapper; }

    @Override
    @DeleteMapping("/{id:\\d+}")
    public R<?> delete(@PathVariable Long id) {
        long used = beneficiaryMapper.selectCount(new QueryWrapper<YktBeneficiary>()
                .eq("BANK_ID", id).or().eq("PUBLIC_BANK_ID", id));
        if (used > 0) throw new BizException("该银行已被 " + used + " 条补贴对象记录引用（开户银行/公共账户开户行），不允许删除");
        return super.delete(id);
    }

    /**
     * 列表回显用：按 id 集或名称集批量取银行（全国联行号库 1.7 万行，不再整表下发前端）。
     * ids/names 都是当前页出现的少量值（≤几十），单次 IN 远低于 ORA-01795；仍分块 900 兜底。
     */
    @GetMapping("/resolve")
    public R<List<YktBank>> resolve(@RequestParam(required = false) String ids,
                                    @RequestParam(required = false) String names) {
        List<String> keys = split(ids != null ? ids : names);
        if (keys.isEmpty()) return R.ok(List.of());
        String col = ids != null && !ids.isBlank() ? "ID" : "BANK_NAME";
        List<YktBank> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += 900) {
            QueryWrapper<YktBank> w = new QueryWrapper<>();
            w.select("ID", "BANK_NAME", "UNION_CODE", "BANK_CODE");
            w.in(col, keys.subList(i, Math.min(i + 900, keys.size())));
            out.addAll(mapper.selectList(w));
        }
        return R.ok(out);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    @Override
    protected QueryWrapper<YktBank> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktBank> w = super.buildQuery(params);
        like(w, "BANK_CODE", params.get("bankCode"));
        like(w, "BANK_NAME", params.get("bankName"));
        like(w, "UNION_CODE", params.get("unionCode"));
        // 下拉远程搜索：一个关键字同时匹配 名称/联行号/行号（单列 like 是 AND 语义，行号搜索必须走 OR）
        Object kw = params.get("kw");
        if (kw != null && !kw.toString().isBlank()) {
            String k = kw.toString().trim();
            w.and(x -> x.like("BANK_NAME", k).or().like("UNION_CODE", k).or().like("BANK_CODE", k));
        }
        return w;
    }

    private static void like(QueryWrapper<YktBank> w, String col, Object v) {
        if (v != null && !v.toString().isBlank()) w.like(col, v.toString().trim());
    }
}
