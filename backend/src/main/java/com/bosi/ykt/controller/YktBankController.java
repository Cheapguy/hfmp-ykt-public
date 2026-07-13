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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Override
    protected QueryWrapper<YktBank> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktBank> w = super.buildQuery(params);
        like(w, "BANK_CODE", params.get("bankCode"));
        like(w, "BANK_NAME", params.get("bankName"));
        like(w, "UNION_CODE", params.get("unionCode"));
        return w;
    }

    private static void like(QueryWrapper<YktBank> w, String col, Object v) {
        if (v != null && !v.toString().isBlank()) w.like(col, v.toString().trim());
    }
}
