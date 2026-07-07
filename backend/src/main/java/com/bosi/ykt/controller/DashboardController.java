package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.*;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.DataScopeResolver;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 工作台聚合：补贴项目 / 对象 / 批次 / 花名册 概览（按当前用户县域范围统计）。 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final YktProjectMapper projectMapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    private final YktBatchMapper batchMapper;
    private final YktRosterMapper rosterMapper;
    private final DataScopeResolver dataScope;

    private <T> QueryWrapper<T> tenant() {
        QueryWrapper<T> w = new QueryWrapper<>();
        Long t = UserContext.currentTenantId();
        if (t != null) w.eq("TENANT_ID", t);
        return w;
    }

    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        // 各计数按当前用户县域范围收窄（管理员=全州；县/乡镇=本县/本乡镇）
        QueryWrapper<YktProject> pw = tenant();
        dataScope.applyProject(pw, "PROJECT_CODE");
        QueryWrapper<YktBeneficiary> bw = tenant();
        dataScope.applyTown(bw, "TOWN_ID");
        QueryWrapper<YktBatch> baw = tenant();
        dataScope.applyTown(baw, "TOWN_ID");
        QueryWrapper<YktRoster> rw = tenant();
        dataScope.applyBatchTown(rw, "BATCH_ID");

        Map<String, Object> m = new HashMap<>();
        m.put("projectCount", projectMapper.selectCount(pw));
        m.put("beneficiaryCount", beneficiaryMapper.selectCount(bw));
        m.put("batchCount", batchMapper.selectCount(baw));
        m.put("rosterCount", rosterMapper.selectCount(rw));
        return R.ok(m);
    }
}
