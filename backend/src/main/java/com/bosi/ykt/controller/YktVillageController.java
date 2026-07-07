package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.entity.YktVillage;
import com.bosi.ykt.mapper.YktVillageMapper;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 村组维护。可按乡镇过滤 */
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
}
