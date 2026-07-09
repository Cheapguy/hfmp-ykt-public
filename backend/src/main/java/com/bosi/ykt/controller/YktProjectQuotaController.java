package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktIndicator;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktProjectQuota;
import com.bosi.ykt.mapper.YktIndicatorMapper;
import com.bosi.ykt.mapper.YktProjectMapper;
import com.bosi.ykt.mapper.YktProjectQuotaMapper;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 项目额度（指标）挂接。预算管理一体化 - 预算执行 - 集中支付 - 项目额度规则设置。手册 §十二。
 * 项目由一卡通系统授权显示；只有热点分类含"一卡通"的指标可挂接；支持按优先级/可用金额使用规则。
 */
@RestController
@RequestMapping("/pay/quota")
@RequiredArgsConstructor
public class YktProjectQuotaController {

    private final YktProjectQuotaMapper mapper;
    private final YktIndicatorMapper indicatorMapper;
    private final YktProjectMapper projectMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    static final Map<String, String> RULE_LABEL = Map.of(
            "PRIORITY", "按优先级使用",
            "ASC", "按可用金额从小到大使用",
            "DESC", "按可用金额从大到小使用");

    private Long tid() { return UserContext.currentTenantId(); }

    /** 项目县域越权兜底：复用 applyProject 过滤 —— 该 projectId 不在本人可见项目范围内则拒。 */
    private void assertProjectScope(Long projectId) {
        if (projectId == null) return;
        QueryWrapper<YktProject> w = new QueryWrapper<YktProject>().eq("ID", projectId);
        dataScope.applyProject(w, "PROJECT_CODE");
        if (projectMapper.selectCount(w) == 0) throw new BizException("无权访问该项目（非本县）");
    }

    /** 某项目已挂接的指标列表（手册截图3：补贴项目+使用规则+指标文号+预算项目+资金性质+可支付余额+优先级…） */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam Long projectId) {
        assertProjectScope(projectId);
        LambdaQueryWrapper<YktProjectQuota> w = new LambdaQueryWrapper<YktProjectQuota>()
                .eq(YktProjectQuota::getProjectId, projectId)
                .orderByAsc(YktProjectQuota::getPriority);
        Long t = tid();
        if (t != null) w.eq(YktProjectQuota::getTenantId, t);
        List<YktProjectQuota> links = mapper.selectList(w);
        if (links.isEmpty()) return R.ok(Collections.emptyList());

        YktProject proj = projectMapper.selectById(projectId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktProjectQuota q : links) {
            YktIndicator ind = q.getIndicatorId() == null ? null : indicatorMapper.selectById(q.getIndicatorId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("indicatorId", q.getIndicatorId());
            m.put("projectCode", proj == null ? null : proj.getProjectCode());
            m.put("projectName", proj == null ? null : proj.getProjectName());
            m.put("useRule", q.getUseRule());
            m.put("useRuleLabel", RULE_LABEL.getOrDefault(q.getUseRule(), "按优先级使用"));
            m.put("priority", q.getPriority());
            putIndicator(m, ind);
            out.add(m);
        }
        return R.ok(out);
    }

    /** 新增挂接弹窗：可挂接的"一卡通"指标（热点分类=一卡通且未被本项目挂接），支持筛选 */
    @GetMapping("/indicators")
    public R<List<YktIndicator>> indicators(@RequestParam Long projectId,
                                            @RequestParam(required = false) String indicatorNo,
                                            @RequestParam(required = false) String govEcon,
                                            @RequestParam(required = false) String deptEcon,
                                            @RequestParam(required = false) String budgetProject) {
        assertProjectScope(projectId);
        Long t = tid();
        // 已挂接的指标 id（排除）
        LambdaQueryWrapper<YktProjectQuota> lw = new LambdaQueryWrapper<YktProjectQuota>()
                .eq(YktProjectQuota::getProjectId, projectId);
        if (t != null) lw.eq(YktProjectQuota::getTenantId, t);
        Set<Long> linked = new HashSet<>();
        mapper.selectList(lw).forEach(q -> { if (q.getIndicatorId() != null) linked.add(q.getIndicatorId()); });

        QueryWrapper<YktIndicator> w = new QueryWrapper<>();
        if (t != null) w.eq("TENANT_ID", t);
        w.eq("HOT_CLASS_NAME", "一卡通");   // 只显示热点分类是"一卡通"的指标
        if (indicatorNo != null && !indicatorNo.isBlank()) w.like("INDICATOR_NO", indicatorNo.trim());
        if (govEcon != null && !govEcon.isBlank())
            w.and(x -> x.like("GOV_ECON_CODE", govEcon.trim()).or().like("GOV_ECON_NAME", govEcon.trim()));
        if (deptEcon != null && !deptEcon.isBlank())
            w.and(x -> x.like("DEPT_ECON_CODE", deptEcon.trim()).or().like("DEPT_ECON_NAME", deptEcon.trim()));
        if (budgetProject != null && !budgetProject.isBlank()) w.like("BUDGET_PROJECT", budgetProject.trim());
        w.orderByAsc("ID");
        List<YktIndicator> all = indicatorMapper.selectList(w);
        all.removeIf(i -> linked.contains(i.getId()));
        return R.ok(all);
    }

    @Data public static class LinkReq { private Long projectId; private List<Long> indicatorIds; }

    /** 新增挂接：保存勾选的指标（默认按优先级，优先级顺延） */
    @PostMapping("/link")
    @Transactional(rollbackFor = Exception.class)
    public R<?> link(@RequestBody LinkReq req) {
        if (req.getProjectId() == null) throw new BizException("请选择项目");
        assertProjectScope(req.getProjectId());
        if (req.getIndicatorIds() == null || req.getIndicatorIds().isEmpty()) throw new BizException("请选择指标");
        Long t = tid();
        Integer maxP = mapper.selectList(new LambdaQueryWrapper<YktProjectQuota>()
                        .eq(YktProjectQuota::getProjectId, req.getProjectId()))
                .stream().map(YktProjectQuota::getPriority).filter(Objects::nonNull).max(Integer::compareTo).orElse(0);
        int p = maxP;
        int n = 0;
        for (Long indId : req.getIndicatorIds()) {
            YktIndicator ind = indicatorMapper.selectById(indId);
            if (ind == null) continue;
            YktProjectQuota q = new YktProjectQuota();
            q.setProjectId(req.getProjectId());
            q.setIndicatorId(indId);
            q.setIndicatorCode(ind.getIndicatorNo());
            q.setIndicatorName(ind.getBudgetProject());
            q.setPriority(++p);
            q.setUseRule("PRIORITY");
            q.setQuotaAmount(ind.getAvailableAmount());
            mapper.insert(q);
            n++;
        }
        return R.ok(Map.of("count", n));
    }

    @Data public static class UnlinkReq { private List<Long> ids; }

    /** 撤销挂接（县域越权兜底：只可撤本人可见项目的挂接） */
    @PostMapping("/unlink")
    public R<?> unlink(@RequestBody UnlinkReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("请选择要撤销的挂接");
        for (Long id : req.getIds()) {
            YktProjectQuota q = mapper.selectById(id);
            if (q == null) continue;
            assertProjectScope(q.getProjectId());
            mapper.deleteById(id);
        }
        return R.ok();
    }

    @Data public static class RuleItem { private Long id; private Integer priority; }
    @Data public static class RuleReq { private Long projectId; private String useRule; private List<RuleItem> items; }

    /** 使用规则设置：额度使用类型（PRIORITY/ASC/DESC）+ 每条优先级 */
    @PostMapping("/save-rule")
    @Transactional(rollbackFor = Exception.class)
    public R<?> saveRule(@RequestBody RuleReq req) {
        if (req.getProjectId() == null) throw new BizException("请选择项目");
        assertProjectScope(req.getProjectId());
        String rule = req.getUseRule() == null ? "PRIORITY" : req.getUseRule();
        if (!RULE_LABEL.containsKey(rule)) throw new BizException("额度使用类型不合法");
        Map<Long, Integer> prio = new HashMap<>();
        if (req.getItems() != null) for (RuleItem it : req.getItems()) if (it.getId() != null) prio.put(it.getId(), it.getPriority());

        for (YktProjectQuota q : mapper.selectList(new LambdaQueryWrapper<YktProjectQuota>()
                .eq(YktProjectQuota::getProjectId, req.getProjectId()))) {
            YktProjectQuota u = new YktProjectQuota();
            u.setId(q.getId());
            u.setUseRule(rule);
            if (prio.containsKey(q.getId())) u.setPriority(prio.get(q.getId()));
            mapper.updateById(u);
        }
        return R.ok();
    }

    /** 共用：把指标字段塞进展示 map */
    static void putIndicator(Map<String, Object> m, YktIndicator ind) {
        m.put("indicatorNo", ind == null ? null : ind.getIndicatorNo());
        m.put("govEconCode", ind == null ? null : ind.getGovEconCode());
        m.put("govEconName", ind == null ? null : ind.getGovEconName());
        m.put("deptEconCode", ind == null ? null : ind.getDeptEconCode());
        m.put("deptEconName", ind == null ? null : ind.getDeptEconName());
        m.put("budgetProject", ind == null ? null : ind.getBudgetProject());
        m.put("fundNature", ind == null ? null : ind.getFundNature());
        m.put("issuedAmount", ind == null ? null : ind.getIssuedAmount());
        m.put("frozenAmount", ind == null ? null : ind.getFrozenAmount());
        m.put("paidAmount", ind == null ? null : ind.getPaidAmount());
        m.put("availableAmount", ind == null ? null : ind.getAvailableAmount());
        m.put("budgetUnit", ind == null ? null : ind.getBudgetUnit());
        m.put("indicatorDesc", ind == null ? null : ind.getIndicatorDesc());
    }
}
