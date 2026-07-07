package com.bosi.ykt.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.R;
import com.bosi.ykt.controller.dto.GrantDetailExport;
import com.bosi.ykt.entity.YktAuditLog;
import com.bosi.ykt.entity.YktBatch;
import com.bosi.ykt.entity.YktGrantDetail;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.DataScopeResolver;
import com.bosi.ykt.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 系统管理 - 查询（只读批次查询，跨全部审核状态）。对应生产「系统管理 → 查询」。
 * 列：进度(查看流程进度) / 项目 / 批次号 / 批次名称 / 单位 / 审核状态 / 申请发放人数·金额 / 实际发放人数·金额。
 */
@RestController
@RequestMapping("/dept/query")
@RequiredArgsConstructor
public class YktQueryController {

    private final YktBatchMapper batchMapper;
    private final YktProjectMapper projectMapper;
    private final SysOrgMapper orgMapper;
    private final YktAuditLogMapper logMapper;
    private final YktGrantDetailMapper grantMapper;
    private final DataScopeResolver dataScope;

    /** "1,2,3" -> [1,2,3]；空返回空列表 */
    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) ids.add(Long.valueOf(s));
        }
        return ids;
    }

    /** 审核状态显示（按当前待审岗推断已审到哪一步）；DONE=终审（终审≠发送，发送状态看批次状态） */
    private static final Map<String, String> AUDIT_LABEL = Map.of(
            "DRAFT",       "待编制",
            "TOWN_AUDIT",  "待乡镇审核",
            "DEPT_OP",     "待部门经办初审",
            "DEPT_REVIEW", "待部门复核",
            "DONE",        "终审"
    );

    @GetMapping("/page")
    public R<IPage<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) String batchCode) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        if (projectId != null) w.eq("PROJECT_ID", projectId);
        if (batchCode != null && !batchCode.isBlank()) w.like("BATCH_CODE", batchCode);
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：乡镇只见本乡镇批次
        w.orderByDesc("ID");

        Page<YktBatch> p = batchMapper.selectPage(new Page<>(pageNum, pageSize), w);

        Map<Long, String> projName = new HashMap<>();
        projectMapper.selectList(null).forEach(pr -> projName.put(pr.getId(), pr.getProjectName()));
        Map<Long, String> orgName = new HashMap<>();
        orgMapper.selectList(null).forEach(o -> orgName.put(o.getId(), o.getOrgName()));

        List<Map<String, Object>> records = new ArrayList<>();
        for (YktBatch b : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("projectName", projName.get(b.getProjectId()));
            m.put("unitName", orgName.get(b.getTownId()));
            m.put("auditStatus", AUDIT_LABEL.getOrDefault(b.getAuditStage(), b.getAuditStage()));
            m.put("planCount", b.getPlanCount());
            m.put("planAmount", b.getPlanAmount());
            m.put("actualCount", b.getActualCount());
            m.put("actualAmount", b.getActualAmount());
            m.put("refundAmount", b.getRefundAmount());
            m.put("returnAmount", b.getReturnAmount());
            m.put("stopAmount", b.getStopAmount());
            records.add(m);
        }
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(records);
        return R.ok(out);
    }

    /** 批次号下拉：选中项目后动态加载该项目下的批次（id+号+名称），供前端 el-select 远程搜索 */
    @GetMapping("/batches")
    public R<List<Map<String, Object>>> batches(@RequestParam Long projectId,
                                                @RequestParam(required = false) String keyword) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.eq("PROJECT_ID", projectId);
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like("BATCH_CODE", keyword).or().like("BATCH_NAME", keyword));
        }
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离
        w.orderByDesc("ID");
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktBatch b : batchMapper.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 流程进度（与发放数据审核共用流水表） */
    @GetMapping("/{id}/history")
    public R<List<YktAuditLog>> history(@PathVariable Long id) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<YktAuditLog>()
                .eq(YktAuditLog::getBatchId, id).orderByAsc(YktAuditLog::getSeqNo)));
    }

    /** 花名册：选中批次的发放明细（分页） */
    @GetMapping("/roster")
    public R<IPage<YktGrantDetail>> roster(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "50") long pageSize,
                                           @RequestParam String batchIds) {
        List<Long> ids = parseIds(batchIds);
        if (ids.isEmpty()) return R.ok(new Page<>(pageNum, pageSize, 0));
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.in("BATCH_ID", ids);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离：防手动传别县 batchId
        w.orderByAsc("BATCH_ID", "SORT_NO");
        return R.ok(grantMapper.selectPage(new Page<>(pageNum, pageSize), w));
    }

    /** 村组补贴汇总：选中批次按 村(居)委会 汇总 发放户数/人数/金额 */
    @GetMapping("/village-summary")
    public R<List<Map<String, Object>>> villageSummary(@RequestParam String batchIds) {
        List<Long> ids = parseIds(batchIds);
        if (ids.isEmpty()) return R.ok(Collections.emptyList());
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.in("BATCH_ID", ids);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离

        // 按村组聚合：发放户数(按户主身份证去重)/发放人数/金额
        Map<String, long[]> households = new LinkedHashMap<>();   // village -> [personCount]
        Map<String, java.math.BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Set<String>> hhSet = new LinkedHashMap<>();
        for (YktGrantDetail d : grantMapper.selectList(w)) {
            String v = d.getVillageName() == null ? "" : d.getVillageName();
            households.computeIfAbsent(v, k -> new long[1])[0]++;
            amounts.merge(v, d.getAmount() == null ? java.math.BigDecimal.ZERO : d.getAmount(), java.math.BigDecimal::add);
            hhSet.computeIfAbsent(v, k -> new HashSet<>()).add(d.getHolderIdCard() == null ? d.getBeneficiaryIdCard() : d.getHolderIdCard());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String v : households.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("villageName", v);
            m.put("householdCount", hhSet.get(v).size());
            m.put("personCount", households.get(v)[0]);
            m.put("amount", amounts.get(v));
            out.add(m);
        }
        out.sort((a, b) -> ((java.math.BigDecimal) b.get("amount")).compareTo((java.math.BigDecimal) a.get("amount")));
        return R.ok(out);
    }

    /** 导出花名册为 Excel（选中批次） */
    @GetMapping("/roster/export")
    public void exportRoster(@RequestParam String batchIds, HttpServletResponse resp) throws Exception {
        List<Long> ids = parseIds(batchIds);
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        if (ids.isEmpty()) w.apply("1=0");
        else w.in("BATCH_ID", ids);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离
        w.orderByAsc("BATCH_ID", "SORT_NO");

        DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<GrantDetailExport> rows = grantMapper.selectList(w).stream().map(d -> {
            GrantDetailExport e = new GrantDetailExport();
            e.setSortNo(d.getSortNo());
            e.setPayStatus(d.getPayStatus());
            e.setHolderName(d.getHolderName());
            e.setHolderIdCard(d.getHolderIdCard());
            e.setPayeeName(d.getPayeeName());
            e.setPayeeIdCard(d.getPayeeIdCard());
            e.setBankAccount(d.getBankAccount());
            e.setBankName(d.getBankName());
            e.setVillageName(d.getVillageName());
            e.setGroupName(d.getGroupName());
            e.setBeneficiaryName(d.getBeneficiaryName());
            e.setBeneficiaryIdCard(d.getBeneficiaryIdCard());
            e.setPhone(d.getPhone());
            e.setResidence(d.getResidence());
            e.setAge(d.getAge());
            e.setAmount(d.getAmount());
            e.setFillDate(d.getFillDate() == null ? null : d.getFillDate().format(df));
            return e;
        }).collect(Collectors.toList());

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("花名册", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        resp.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        EasyExcel.write(resp.getOutputStream(), GrantDetailExport.class).sheet("花名册").doWrite(rows);
    }
}
