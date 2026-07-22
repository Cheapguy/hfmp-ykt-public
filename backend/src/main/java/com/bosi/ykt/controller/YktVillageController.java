package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktVillage;
import com.bosi.ykt.mapper.YktVillageMapper;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** 村组维护。手册 §六：可按乡镇过滤 */
@RestController
@RequestMapping("/setup/village")
@RequiredArgsConstructor
public class YktVillageController extends BaseCrudController<YktVillageMapper, YktVillage> {
    private final YktVillageMapper mapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;
    @Override protected YktVillageMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktVillage> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktVillage> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        Object townId = params.get("townId");
        if (townId != null && !"".equals(townId)) w.eq("TOWN_ID", townId);
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离
        w.orderByAsc("SORT_NO");
        return w;
    }

    /** 县域越权兜底（写路径）：委托 DataScopeResolver 单一真源。 */
    private void assertTownScope(Long townId) {
        dataScope.assertTown(townId, "该村组");
    }

    /** 按 id 兜底：反查现存记录的乡镇后校验。 */
    private YktVillage assertExisting(Long id) {
        YktVillage v = id == null ? null : mapper.selectById(id);
        if (v == null) throw new BizException("村组不存在");
        assertTownScope(v.getTownId());
        return v;
    }

    /** detail 读取越权兜底：禁止跨县凭 id 越权读村组。 */
    @Override
    protected void assertReadable(YktVillage e) { assertTownScope(e.getTownId()); }

    @Override
    public R<?> create(@RequestBody YktVillage e) {
        assertTownScope(e.getTownId());
        return super.create(e);
    }

    @Override
    public R<?> update(@RequestBody YktVillage e) {
        if (e.getId() == null) throw new BizException("缺少 id");
        assertExisting(e.getId());
        if (e.getTownId() != null) assertTownScope(e.getTownId());
        return super.update(e);
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        assertExisting(id);
        return super.delete(id);
    }
}
