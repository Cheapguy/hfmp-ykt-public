package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 补贴对象库。手册 §八：新增/修改/删除/导入/导出/引用/注销/批量修改 */
@RestController
@RequestMapping("/setup/beneficiary")
@RequiredArgsConstructor
public class YktBeneficiaryController extends BaseCrudController<YktBeneficiaryMapper, YktBeneficiary> {
    private final YktBeneficiaryMapper mapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;
    @Override protected YktBeneficiaryMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktBeneficiary> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktBeneficiary> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        like(w, "NAME", params.get("name"));
        like(w, "ID_CARD", params.get("idCard"));
        like(w, "HEAD_NAME", params.get("headName"));
        like(w, "HEAD_ID_CARD", params.get("headIdCard"));
        like(w, "BANK_ACCOUNT", params.get("bankAccount"));
        eq(w, "TOWN_ID", params.get("townId"));
        eq(w, "VILLAGE_ID", params.get("villageId"));
        eq(w, "IS_RURAL", params.get("isRural"));
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离
        w.orderByAsc("ID");
        return w;
    }

    private static void like(QueryWrapper<YktBeneficiary> w, String col, Object v) {
        if (v != null && !"".equals(v)) w.like(col, v);
    }

    private static void eq(QueryWrapper<YktBeneficiary> w, String col, Object v) {
        if (v != null && !"".equals(v)) w.eq(col, v);
    }

    /** 县域越权兜底（写路径）：目标乡镇不在本人可见范围则拒。 */
    private void assertTownScope(Long townId) {
        java.util.Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        if (townId == null || !towns.contains(townId)) throw new com.bosi.ykt.common.BizException("无权操作该补贴对象（非本县数据）");
    }

    /** 按 id 兜底：反查现存记录的乡镇后校验。 */
    private YktBeneficiary assertExisting(Long id) {
        YktBeneficiary b = id == null ? null : mapper.selectById(id);
        if (b == null) throw new com.bosi.ykt.common.BizException("补贴对象不存在");
        assertTownScope(b.getTownId());
        return b;
    }

    /** detail 读取越权兜底：补贴对象含身份证/银行卡号，禁止跨县凭 id 越权读。 */
    @Override
    protected void assertReadable(YktBeneficiary e) { assertTownScope(e.getTownId()); }

    @Override
    public R<?> create(@org.springframework.web.bind.annotation.RequestBody YktBeneficiary e) {
        assertTownScope(e.getTownId());
        return super.create(e);
    }

    @Override
    public R<?> update(@org.springframework.web.bind.annotation.RequestBody YktBeneficiary e) {
        if (e.getId() == null) throw new com.bosi.ykt.common.BizException("缺少 id");
        assertExisting(e.getId());
        if (e.getTownId() != null) assertTownScope(e.getTownId());   // 改乡镇归属也须在本县内
        return super.update(e);
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        assertExisting(id);
        return super.delete(id);
    }

    /** 引用：按身份证号查补贴对象（跨乡镇引用前需沟通完善信息）。手册 §八-6 */
    @GetMapping("/refer")
    public R<YktBeneficiary> refer(@RequestParam String idCard) {
        return R.ok(mapper.selectOne(new QueryWrapper<YktBeneficiary>()
                .eq("ID_CARD", idCard).last("AND ROWNUM=1")));
    }

    /** 注销。手册 §八-7（状态置 0） */
    @PostMapping("/{id}/cancel")
    public R<?> cancel(@PathVariable Long id) {
        assertExisting(id);
        YktBeneficiary b = new YktBeneficiary();
        b.setId(id);
        b.setStatus("0");
        mapper.updateById(b);
        return R.ok();
    }

    /** 取消注销（状态恢复 1） */
    @PostMapping("/{id}/uncancel")
    public R<?> uncancel(@PathVariable Long id) {
        assertExisting(id);
        YktBeneficiary b = new YktBeneficiary();
        b.setId(id);
        b.setStatus("1");
        mapper.updateById(b);
        return R.ok();
    }
}
