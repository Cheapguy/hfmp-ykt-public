package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.*;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 惠民报表。手册「惠民报表」模块。
 * 数据源 = 已发放(payStatus=已支付)的花名册明细 YKT_GRANT_DETAIL，关联批次/项目/乡镇。
 *  - 乡镇发放情况个人查询：逐人发放明细 + 多条件筛选 + 合计金额。
 *  - 乡镇项目发放情况查询：按 项目+乡镇 汇总发放人数/金额。
 */
@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class YktReportController {

    private static final String PAID = "已支付";

    private final YktGrantDetailMapper grantMapper;
    private final YktBatchMapper batchMapper;
    private final YktProjectMapper projectMapper;
    private final YktStdItemMapper stdItemMapper;
    private final SysOrgMapper orgMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    private Long tid() { return UserContext.currentTenantId(); }

    /** 个人发放明细查询条件（每次构造新的 wrapper，供分页与合计复用） */
    private QueryWrapper<YktGrantDetail> personWrapper(Long projectId, Long batchId, String holderName,
            String holderIdCard, String beneficiaryName, String beneficiaryIdCard) {
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        w.eq("PAY_STATUS", PAID);
        // 按项目筛：用子查询，避免大表批次主键拼超长 IN（ORA-01795）
        if (projectId != null) w.inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE PROJECT_ID = " + projectId);
        if (batchId != null) w.eq("BATCH_ID", batchId);
        if (notBlank(holderName)) w.like("HOLDER_NAME", holderName.trim());
        if (notBlank(holderIdCard)) w.like("HOLDER_ID_CARD", holderIdCard.trim());
        if (notBlank(beneficiaryName)) w.like("BENEFICIARY_NAME", beneficiaryName.trim());
        if (notBlank(beneficiaryIdCard)) w.like("BENEFICIARY_ID_CARD", beneficiaryIdCard.trim());
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离
        return w;
    }

    /** 乡镇发放情况个人查询：分页明细 + 全量合计金额 */
    @GetMapping("/person")
    public R<Map<String, Object>> person(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "20") long pageSize,
                                         @RequestParam(required = false) Long projectId,
                                         @RequestParam(required = false) Long batchId,
                                         @RequestParam(required = false) String holderName,
                                         @RequestParam(required = false) String holderIdCard,
                                         @RequestParam(required = false) String beneficiaryName,
                                         @RequestParam(required = false) String beneficiaryIdCard) {
        QueryWrapper<YktGrantDetail> w = personWrapper(projectId, batchId, holderName, holderIdCard, beneficiaryName, beneficiaryIdCard);
        w.orderByDesc("BATCH_ID").orderByAsc("SORT_NO");
        Page<YktGrantDetail> p = grantMapper.selectPage(new Page<>(pageNum, pageSize), w);

        Map<Long, YktBatch> batchCache = new HashMap<>();
        Map<Long, YktProject> projCache = new HashMap<>();
        Map<Long, String> orgName = new HashMap<>();
        orgMapper.selectList(null).forEach(o -> orgName.put(o.getId(), o.getOrgName()));

        List<Map<String, Object>> records = new ArrayList<>();
        for (YktGrantDetail d : p.getRecords()) {
            YktBatch b = batchCache.computeIfAbsent(d.getBatchId(), batchMapper::selectById);
            YktProject pj = b == null ? null : projCache.computeIfAbsent(b.getProjectId(), projectMapper::selectById);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectName", pj == null ? null : pj.getProjectName());
            m.put("batchName", b == null ? null : b.getBatchName());
            m.put("holderIdCard", d.getHolderIdCard());
            m.put("holderName", d.getHolderName());
            m.put("beneficiaryIdCard", d.getBeneficiaryIdCard());
            m.put("beneficiaryName", d.getBeneficiaryName());
            m.put("townName", b == null ? null : orgName.get(b.getTownId()));
            m.put("villageName", d.getVillageName());
            m.put("groupName", d.getGroupName());
            m.put("amount", d.getAmount());
            m.put("grantTime", b == null ? null : b.getGrantTime());
            records.add(m);
        }

        // 全量合计（同条件，不分页）
        QueryWrapper<YktGrantDetail> ws = personWrapper(projectId, batchId, holderName, holderIdCard, beneficiaryName, beneficiaryIdCard);
        ws.select("NVL(SUM(AMOUNT),0) AS TOTAL");
        List<Map<String, Object>> sumRows = grantMapper.selectMaps(ws);
        Object totalAmount = sumRows.isEmpty() ? BigDecimal.ZERO : sumRows.get(0).get("TOTAL");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", p.getTotal());
        out.put("totalAmount", totalAmount);
        return R.ok(out);
    }

    /** 明细查询条件（乡镇/村组/批次/项目 + 已支付），分页与合计复用 */
    private QueryWrapper<YktGrantDetail> detailWrapper(Long projectId, Long batchId, Long townId, String village) {
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        w.eq("PAY_STATUS", PAID);
        if (projectId != null) w.inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE PROJECT_ID = " + projectId);
        if (batchId != null) w.eq("BATCH_ID", batchId);
        if (townId != null) w.inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE TOWN_ID = " + townId);
        if (notBlank(village)) w.eq("VILLAGE_NAME", village.trim());
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离
        return w;
    }

    /** 乡镇发放情况明细查询：逐人发放明细（项目/批次/批次下达时间/乡镇/村组/户主/享受人/金额/时间） */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "20") long pageSize,
                                         @RequestParam(required = false) Long projectId,
                                         @RequestParam(required = false) Long batchId,
                                         @RequestParam(required = false) Long townId,
                                         @RequestParam(required = false) String village) {
        QueryWrapper<YktGrantDetail> w = detailWrapper(projectId, batchId, townId, village);
        w.orderByDesc("BATCH_ID").orderByAsc("SORT_NO");
        Page<YktGrantDetail> p = grantMapper.selectPage(new Page<>(pageNum, pageSize), w);

        Map<Long, YktBatch> batchCache = new HashMap<>();
        Map<Long, YktProject> projCache = new HashMap<>();
        Map<Long, String> orgName = new HashMap<>();
        orgMapper.selectList(null).forEach(o -> orgName.put(o.getId(), o.getOrgName()));

        List<Map<String, Object>> records = new ArrayList<>();
        for (YktGrantDetail d : p.getRecords()) {
            YktBatch b = batchCache.computeIfAbsent(d.getBatchId(), batchMapper::selectById);
            YktProject pj = b == null ? null : projCache.computeIfAbsent(b.getProjectId(), projectMapper::selectById);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectName", pj == null ? null : pj.getProjectName());
            m.put("batchName", b == null ? null : b.getBatchName());
            m.put("issueTime", b == null ? null : b.getCreateTime());
            m.put("townName", b == null ? null : orgName.get(b.getTownId()));
            m.put("villageName", d.getVillageName());
            m.put("groupName", d.getGroupName());
            m.put("holderIdCard", d.getHolderIdCard());
            m.put("holderName", d.getHolderName());
            m.put("beneficiaryIdCard", d.getBeneficiaryIdCard());
            m.put("beneficiaryName", d.getBeneficiaryName());
            m.put("amount", d.getAmount());
            m.put("grantTime", b == null ? null : b.getGrantTime());
            records.add(m);
        }

        QueryWrapper<YktGrantDetail> ws = detailWrapper(projectId, batchId, townId, village);
        ws.select("NVL(SUM(AMOUNT),0) AS TOTAL");
        List<Map<String, Object>> sumRows = grantMapper.selectMaps(ws);
        Object totalAmount = sumRows.isEmpty() ? BigDecimal.ZERO : sumRows.get(0).get("TOTAL");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", p.getTotal());
        out.put("totalAmount", totalAmount);
        return R.ok(out);
    }

    /**
     * 乡镇项目发放情况查询：按批次列出「申请 vs 发放」对比（部门/项目/批次/月份/乡镇 + 申请金额人数 + 发放金额人数）。
     * 申请 = 批次计划(planAmount/planCount)，发放 = 批次实发(actualAmount/actualCount)；数据范围 = 已发放批次。
     */
    @GetMapping("/project")
    public R<List<Map<String, Object>>> project(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) Long batchId,
                                                @RequestParam(required = false) String competentDept,
                                                @RequestParam(required = false) Long townId) {
        List<Map<String, Object>> agg = grantMapper.reportBatchGrant(tid(), projectId, batchId, townId,
                notBlank(competentDept) ? competentDept.trim() : null);
        filterByTown(agg);   // 县域隔离

        Map<Long, YktProject> projCache = new HashMap<>();
        Map<Long, String> orgName = new HashMap<>();
        orgMapper.selectList(null).forEach(o -> orgName.put(o.getId(), o.getOrgName()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : agg) {
            Long pid = a.get("PROJECTID") == null ? null : ((Number) a.get("PROJECTID")).longValue();
            Long tw = a.get("TOWNID") == null ? null : ((Number) a.get("TOWNID")).longValue();
            YktProject pj = pid == null ? null : projCache.computeIfAbsent(pid, projectMapper::selectById);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("competentDept", pj == null ? null : pj.getCompetentDept());
            m.put("projectName", pj == null ? null : pj.getProjectName());
            m.put("batchName", a.get("BATCHNAME"));
            m.put("month", a.get("MON"));
            m.put("townName", tw == null ? null : orgName.get(tw));
            m.put("applyAmount", a.get("APPLYAMOUNT"));
            m.put("applyCount", a.get("APPLYCOUNT"));
            m.put("grantAmount", a.get("GRANTAMOUNT"));
            m.put("grantCount", a.get("GRANTCOUNT"));
            out.add(m);
        }
        return R.ok(out);
    }

    /**
     * 部门项目发放情况查询：按 部门→项目→批次 三层，输出 合计 / 部门小计 / 项目小计 / 明细 行。
     * 指标 = 补贴发放金额(已支付 SUM) + 兑付人次(已支付 COUNT) + 发放时间。
     */
    @GetMapping("/dept-project")
    public R<List<Map<String, Object>>> deptProject(@RequestParam(required = false) Long projectId,
                                                    @RequestParam(required = false) Long batchId,
                                                    @RequestParam(required = false) String competentDept,
                                                    @RequestParam(required = false) Long townId) {
        List<Map<String, Object>> agg = grantMapper.reportBatchGrant(tid(), projectId, batchId, townId,
                notBlank(competentDept) ? competentDept.trim() : null);
        filterByTown(agg);   // 县域隔离

        Map<Long, YktProject> projCache = new HashMap<>();
        // 按 部门 -> 项目 分组（保持插入序），项目下挂批次明细
        Map<String, Map<String, List<Map<String, Object>>>> tree = new LinkedHashMap<>();
        for (Map<String, Object> a : agg) {
            Long pid = a.get("PROJECTID") == null ? null : ((Number) a.get("PROJECTID")).longValue();
            YktProject pj = pid == null ? null : projCache.computeIfAbsent(pid, projectMapper::selectById);
            String dept = pj == null || pj.getCompetentDept() == null ? "（未归部门）" : pj.getCompetentDept();
            String proj = pj == null ? "" : pj.getProjectName();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rowType", "DETAIL");
            detail.put("deptName", dept);
            detail.put("projectName", proj);
            detail.put("batchName", a.get("BATCHNAME"));
            detail.put("grantAmount", a.get("GRANTAMOUNT"));
            detail.put("grantCount", a.get("GRANTCOUNT"));
            detail.put("grantDate", a.get("GRANTDATE"));
            tree.computeIfAbsent(dept, k -> new LinkedHashMap<>())
                .computeIfAbsent(proj, k -> new ArrayList<>()).add(detail);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal grandAmt = BigDecimal.ZERO; long grandCnt = 0;
        // 占位合计行（先加，最后回填）
        Map<String, Object> totalRow = row("TOTAL", "合计", "", "", null, null, null);
        out.add(totalRow);
        for (Map.Entry<String, Map<String, List<Map<String, Object>>>> de : tree.entrySet()) {
            BigDecimal deptAmt = BigDecimal.ZERO; long deptCnt = 0;
            Map<String, Object> deptRow = row("DEPT_SUBTOTAL", de.getKey(), "部门小计", "", null, null, null);
            out.add(deptRow);
            for (Map.Entry<String, List<Map<String, Object>>> pe : de.getValue().entrySet()) {
                BigDecimal projAmt = BigDecimal.ZERO; long projCnt = 0;
                Map<String, Object> projRow = row("PROJECT_SUBTOTAL", de.getKey(), pe.getKey(), "项目小计", null, null, null);
                out.add(projRow);
                for (Map<String, Object> d : pe.getValue()) {
                    out.add(d);
                    BigDecimal amt = num(d.get("grantAmount"));
                    long cnt = num(d.get("grantCount")).longValue();
                    projAmt = projAmt.add(amt); projCnt += cnt;
                }
                projRow.put("grantAmount", projAmt); projRow.put("grantCount", projCnt);
                deptAmt = deptAmt.add(projAmt); deptCnt += projCnt;
            }
            deptRow.put("grantAmount", deptAmt); deptRow.put("grantCount", deptCnt);
            grandAmt = grandAmt.add(deptAmt); grandCnt += deptCnt;
        }
        totalRow.put("grantAmount", grandAmt); totalRow.put("grantCount", grandCnt);
        return R.ok(out);
    }

    private Map<String, Object> row(String type, String dept, String proj, String batch,
                                    Object amt, Object cnt, Object date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowType", type);
        m.put("deptName", dept);
        m.put("projectName", proj);
        m.put("batchName", batch);
        m.put("grantAmount", amt);
        m.put("grantCount", cnt);
        m.put("grantDate", date);
        return m;
    }

    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }

    /** 惠民系统使用情况查询：按 年度+部门 统计各类个数与金额 */
    @GetMapping("/usage")
    public R<List<Map<String, Object>>> usage(@RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(required = false) String competentDept) {
        // 县域隔离：ALL=null 不限；COUNTY/OWN_ORG=乡镇 id 集（空集兜 -1 保证零结果）
        Set<Long> towns = dataScope.allowedTowns();
        List<Long> townIds = towns == null ? null
                : (towns.isEmpty() ? List.of(-1L) : new ArrayList<>(towns));
        List<Map<String, Object>> agg = grantMapper.reportUsage(tid(),
                notBlank(startDate) ? startDate : null, notBlank(endDate) ? endDate : null,
                notBlank(competentDept) ? competentDept.trim() : null, townIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : agg) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", a.get("YR"));
            m.put("deptName", a.get("DEPT"));
            m.put("projectCnt", a.get("PROJECTCNT"));
            m.put("batchCnt", a.get("BATCHCNT"));
            m.put("townCnt", a.get("TOWNCNT"));
            m.put("villageCnt", a.get("VILLAGECNT"));
            m.put("holderCnt", a.get("HOLDERCNT"));
            m.put("beneficiaryCnt", a.get("BENEFICIARYCNT"));
            m.put("amount", a.get("AMOUNT"));
            m.put("actualCnt", a.get("ACTUALCNT"));
            m.put("actualAmount", a.get("ACTUALAMOUNT"));
            out.add(m);
        }
        return R.ok(out);
    }

    // ==================== 财政端报表（migrate_26，菜单 706-709）====================

    /** 县域隔离乡镇 id 集：null=ALL 不限；空集兜 [-1] 保证零结果（reportUsage 同款口径） */
    private List<Long> scopeTownIds() {
        Set<Long> towns = dataScope.allowedTowns();
        return towns == null ? null : (towns.isEmpty() ? List.of(-1L) : new ArrayList<>(towns));
    }

    /** 分项目发放情况统计表：按项目汇总 兑付县区数/兑付人次/兑付金额 */
    @GetMapping("/project-stat")
    public R<List<Map<String, Object>>> projectStat(@RequestParam(required = false) Long projectId,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> agg = grantMapper.reportProjectStat(tid(), projectId,
                notBlank(startDate) ? startDate : null, notBlank(endDate) ? endDate : null, scopeTownIds());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : agg) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectCode", a.get("PROJECTCODE"));
            m.put("projectName", a.get("PROJECTNAME"));
            m.put("countyCnt", a.get("COUNTYCNT"));
            m.put("personCnt", a.get("PERSONCNT"));
            m.put("amount", a.get("AMOUNT"));
            out.add(m);
        }
        return R.ok(out);
    }

    /** 分县区发放情况统计表：合计 / 市州小计 / 县区×项目明细（省市州=SYS_ORG STATE 行） */
    @GetMapping("/county-stat")
    public R<List<Map<String, Object>>> countyStat(@RequestParam(required = false) Long projectId,
                                                   @RequestParam(required = false) String countyCode,
                                                   @RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> agg = grantMapper.reportCountyProject(tid(), projectId, null,
                notBlank(countyCode) ? countyCode : null,
                notBlank(startDate) ? startDate : null, notBlank(endDate) ? endDate : null,
                false, false, scopeTownIds());

        Map<String, String> countyNames = countyNameMap();
        String stateName = stateName();
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal totalAmt = BigDecimal.ZERO; long totalBatch = 0, totalPerson = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> a : agg) {
            String cc = (String) a.get("COUNTYCODE");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowType", "DETAIL");
            m.put("province", stateName);
            m.put("regionCode", cc + "000");
            m.put("regionName", countyNames.getOrDefault(cc, cc));
            m.put("projectCode", a.get("PROJECTCODE"));
            m.put("projectName", a.get("PROJECTNAME"));
            m.put("batchCnt", a.get("BATCHCNT"));
            m.put("personCnt", a.get("PERSONCNT"));
            m.put("amount", a.get("AMOUNT"));
            details.add(m);
            totalAmt = totalAmt.add(num(a.get("AMOUNT")));
            totalBatch += num(a.get("BATCHCNT")).longValue();
            totalPerson += num(a.get("PERSONCNT")).longValue();
        }
        if (!details.isEmpty()) {
            out.add(sumRow("TOTAL", null, "合计", totalBatch, totalPerson, totalAmt));
            // 全州单市州：小计 = 合计（生产同款层级，多市州时按省市州分组）
            out.add(sumRow("SUBTOTAL", stateName, "小计", totalBatch, totalPerson, totalAmt));
            out.addAll(details);
        }
        return R.ok(out);
    }

    private Map<String, Object> sumRow(String type, String province, String label,
                                       long batchCnt, long personCnt, BigDecimal amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowType", type);
        m.put("province", province);
        m.put("regionCode", null);
        m.put("regionName", label);
        m.put("projectCode", null);
        m.put("projectName", null);
        m.put("batchCnt", batchCnt);
        m.put("personCnt", personCnt);
        m.put("amount", amt);
        return m;
    }

    /** 51项补贴项目发放表：国标目录内项目 × 区划，合计 / 项目小计 / 明细 */
    @GetMapping("/std51")
    public R<List<Map<String, Object>>> std51(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String countyCode) {
        Map<String, String> stdNames = new LinkedHashMap<>();
        stdItemMapper.selectList(null).forEach(s -> stdNames.put(s.getCode(), s.getItemName()));
        List<Map<String, Object>> agg = grantMapper.reportCountyProject(tid(), null,
                notBlank(code) ? code : null, notBlank(countyCode) ? countyCode : null,
                null, null, true, false, scopeTownIds());
        return R.ok(shapeItemRegion(agg, pc -> stdNames.getOrDefault(pc, pc)));
    }

    /** 51项目录（下拉数据源） */
    @GetMapping("/std51/items")
    public R<List<YktStdItem>> std51Items() {
        QueryWrapper<YktStdItem> w = new QueryWrapper<>();
        w.orderByAsc("SORT_NO");
        return R.ok(stdItemMapper.selectList(w));
    }

    /** 补贴项目发放表(自设项目)：9 开头自设项目 × 区划，合计 / 项目小计 / 明细 */
    @GetMapping("/self-project")
    public R<List<Map<String, Object>>> selfProject(@RequestParam(required = false) Long projectId,
                                                    @RequestParam(required = false) String countyCode) {
        List<Map<String, Object>> agg = grantMapper.reportCountyProject(tid(), projectId, null,
                notBlank(countyCode) ? countyCode : null,
                null, null, false, true, scopeTownIds());
        return R.ok(shapeItemRegion(agg, null));
    }

    /** 项目×区划 聚合 → 合计 / 项目小计 / 明细 三层（51项/自设项目共用）。nameOf=null 用项目名 */
    private List<Map<String, Object>> shapeItemRegion(List<Map<String, Object>> agg,
                                                      java.util.function.Function<String, String> nameOf) {
        Map<String, String> countyNames = countyNameMap();
        // 按项目分组（保持 SQL 排序：先按县、再按项目编码 → 这里改按项目聚拢）
        Map<String, List<Map<String, Object>>> byProject = new LinkedHashMap<>();
        for (Map<String, Object> a : agg) {
            String key = String.valueOf(a.get("PROJECTID"));
            byProject.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (byProject.isEmpty()) return out;
        Map<String, Object> totalRow = itemRow("TOTAL", "合计", null, 0L, BigDecimal.ZERO);
        out.add(totalRow);
        BigDecimal grandAmt = BigDecimal.ZERO; long grandCnt = 0;
        for (List<Map<String, Object>> group : byProject.values()) {
            String pc = (String) group.get(0).get("PROJECTCODE");
            String pn = (String) group.get(0).get("PROJECTNAME");
            String label = nameOf == null ? pn : nameOf.apply(pc);
            Map<String, Object> subRow = itemRow("PROJECT_SUBTOTAL", "项目小计", null, 0L, BigDecimal.ZERO);
            out.add(subRow);
            BigDecimal projAmt = BigDecimal.ZERO; long projCnt = 0;
            for (Map<String, Object> a : group) {
                String cc = (String) a.get("COUNTYCODE");
                String region = cc + "000-" + countyNames.getOrDefault(cc, cc);
                long cnt = num(a.get("PERSONCNT")).longValue();
                BigDecimal amt = num(a.get("AMOUNT"));
                out.add(itemRow("DETAIL", label, region, cnt, amt));
                projCnt += cnt; projAmt = projAmt.add(amt);
            }
            subRow.put("personCnt", projCnt); subRow.put("amount", projAmt);
            grandCnt += projCnt; grandAmt = grandAmt.add(projAmt);
        }
        totalRow.put("personCnt", grandCnt); totalRow.put("amount", grandAmt);
        return out;
    }

    private Map<String, Object> itemRow(String type, String itemName, String regionName, long cnt, BigDecimal amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowType", type);
        m.put("itemName", itemName);
        m.put("regionName", regionName);
        m.put("personCnt", cnt);
        m.put("amount", amt);
        return m;
    }

    /** 区划下拉（县区）：按数据范围裁剪——ALL 全部县区，COUNTY/OWN_ORG 只回本县 */
    @GetMapping("/counties")
    public R<List<Map<String, Object>>> counties() {
        com.bosi.ykt.security.DataScopeResolver.Ctx c = dataScope.current();
        QueryWrapper<SysOrg> w = new QueryWrapper<>();
        w.eq("ORG_TYPE", "COUNTY").orderByAsc("ORG_CODE");
        if (c.scope != com.bosi.ykt.security.DataScopeResolver.Scope.ALL) w.eq("ORG_CODE", c.countyCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysOrg o : orgMapper.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", o.getOrgCode());
            m.put("name", o.getOrgName());
            out.add(m);
        }
        return R.ok(out);
    }

    private Map<String, String> countyNameMap() {
        Map<String, String> m = new HashMap<>();
        orgMapper.selectList(new QueryWrapper<SysOrg>().eq("ORG_TYPE", "COUNTY"))
                .forEach(o -> m.put(o.getOrgCode(), o.getOrgName()));
        return m;
    }

    private String stateName() {
        List<SysOrg> st = orgMapper.selectList(new QueryWrapper<SysOrg>().eq("ORG_TYPE", "STATE"));
        return st.isEmpty() ? "" : st.get(0).getOrgName();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** 县域隔离：按当前用户可见乡镇裁剪预聚合结果（agg 行含 TOWNID）。ALL 不裁剪。 */
    private void filterByTown(List<Map<String, Object>> agg) {
        Set<Long> allowed = dataScope.allowedTowns();
        if (allowed == null) return;
        agg.removeIf(a -> a.get("TOWNID") == null || !allowed.contains(((Number) a.get("TOWNID")).longValue()));
    }
}
