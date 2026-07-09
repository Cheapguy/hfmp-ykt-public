package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.*;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 发放数据审核（对应生产「发放数据审核 → 审核」菜单）。手册 §十一(二)
 * 岗位链（对齐手册）：
 *   乡镇经办岗送审 → 乡镇审核岗(TOWN_AUDIT) → 部门经办岗(DEPT_OP) → 部门审核岗(DEPT_REVIEW) → 终审(DONE)
 * 每岗：审核(逐岗推进) / 取消审核(逐岗回退) / 退回(直接退回乡镇经办岗重编花名册)。
 * 终审 ≠ 批次发送：部门审核岗通过仅置「终审」(DONE)，status 不变；
 * 真正发送至预算一体化由「批次发送」菜单单独完成。
 */
@RestController
@RequestMapping("/dept/audit")
@RequiredArgsConstructor
public class YktAuditController {

    private final YktBatchMapper batchMapper;
    private final YktAuditLogMapper logMapper;
    private final YktProjectMapper projectMapper;
    private final SysOrgMapper orgMapper;
    private final SysUserMapper userMapper;
    private final YktRosterMapper rosterMapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    /** 待审岗中文名（auditStage = 当前待审岗，对齐生产流程进度） */
    private static final Map<String, String> STAGE = new LinkedHashMap<>() {{
        put("TOWN_AUDIT",  "乡镇审核");
        put("DEPT_OP",     "部门经办审核");
        put("DEPT_REVIEW", "部门领导审核");
        put("DONE",        "结束");
    }};
    /** 审核推进：当前待审岗 -> [下一岗, 操作结果/状态文本] */
    private static final Map<String, String[]> ADVANCE = new LinkedHashMap<>() {{
        put("TOWN_AUDIT",  new String[]{"DEPT_OP",     "乡镇已审"});
        put("DEPT_OP",     new String[]{"DEPT_REVIEW", "部门经办已审"});
        put("DEPT_REVIEW", new String[]{"DONE",        "终审"});
    }};
    /** 批次流转到某阶段后、停在该阶段等待时的状态文本（取消审核撤回目标阶段后回填）。 */
    private static final Map<String, String> ARRIVED_TEXT = Map.of(
            "DRAFT",       "待编制",        // 回录入（乡镇经办重编）
            "TOWN_AUDIT",  "送审",          // 等乡镇审核
            "DEPT_OP",     "乡镇已审",       // 等部门经办
            "DEPT_REVIEW", "部门经办已审"     // 等部门审核
    );

    /**
     * 岗位(userType) -> 该岗"经手"的批次 auditStage（批次停在此阶段=等本岗动作；一岗一阶段，杜绝串岗）。
     * 乡镇经办也在链内：其动作是「送审」(DRAFT→TOWN_AUDIT)，故经手阶段=DRAFT——
     *   待审核落在编制花名册(送审)，已审核=已送出去的清册，取消审核=撤回 DRAFT(退回录入)。
     * FINANCE(财政=支付) 不在审核链，映射缺省=无。
     */
    private static final Map<String, String> STAGE_OF_TYPE = Map.of(
            "TOWN_OP",     "DRAFT",
            "TOWN_AUDIT",  "TOWN_AUDIT",
            "DEPT_OP",     "DEPT_OP",
            "DEPT_AUDIT",  "DEPT_REVIEW"
    );

    /** 审核链顺序（含 DRAFT=乡镇经办送审前；用于判定"已流过本岗"）。 */
    private static final List<String> CHAIN = List.of("DRAFT", "TOWN_AUDIT", "DEPT_OP", "DEPT_REVIEW", "DONE");

    /** 县域越权兜底：批次乡镇不在本人可见范围则拒（同岗跨县批次不可审）。 */
    private void assertScope(YktBatch b) {
        java.util.Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        if (b.getTownId() == null || !towns.contains(b.getTownId()))
            throw new BizException("无权操作批次[" + b.getBatchName() + "]（非本县数据）");
    }

    /** 读路径按批次 id 兜底（history/rosters/check-bank 等直连 id 的接口）。 */
    private void assertScopeById(Long batchId) {
        java.util.Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        YktBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null || b.getTownId() == null || !towns.contains(b.getTownId()))
            throw new BizException("无权访问该批次（非本县数据）");
    }

    /** 当前用户经手的阶段：null=管理员(不限)；""=非审核链岗(无待审/已审)；否则=具体 auditStage。 */
    private String myAuditStage() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null) return "";
        if ("SYS_ADMIN".equals(u.getUserType())) return null;
        return STAGE_OF_TYPE.getOrDefault(u.getUserType(), "");
    }

    /**
     * 审核页数据范围（三个 tab 一套口径，杜绝串岗）：
     * <ul>
     *   <li>待审核(pending)：批次正落在<b>本岗经手阶段</b>（乡镇经办=DRAFT，在审核页恒空——送审在编制花名册）；</li>
     *   <li>已审核(audited)：已<b>流过本岗</b>（本岗之后的阶段，含终审）—— 乡镇经办=已送审的清册，供查看 / 取消审核；</li>
     *   <li>所有(all)：本县全量只读（不按岗位收窄，前端该 tab 不给审核类按钮）。</li>
     * </ul>
     * 管理员不按岗位收窄（pending=未终审 / audited=终审）；财政(非审核链)待审/已审皆空、仅所有可读。
     */
    private void applyAuditScope(QueryWrapper<YktBatch> w, String tab) {
        w.ne("AUDIT_STAGE", "DRAFT");              // 未送审的从不进审核页
        // 所有：对谁都只读可见（含乡镇经办/财政等非审核岗，供追踪清册流到哪个岗；县域由 applyTown 收窄）
        if ("all".equals(tab)) return;
        String my = myAuditStage();
        if ("".equals(my)) { w.apply("1=0"); return; }   // 非审核岗：仅 待审核/已审核 为空
        if (my == null) {                                // 管理员
            if ("audited".equals(tab)) w.eq("AUDIT_STAGE", "DONE");
            else w.ne("AUDIT_STAGE", "DONE");
            return;
        }
        int mi = CHAIN.indexOf(my);
        if ("audited".equals(tab)) w.in("AUDIT_STAGE", CHAIN.subList(mi + 1, CHAIN.size())); // 已流过本岗
        else w.eq("AUDIT_STAGE", my);                    // 待审核：正落本岗
    }

    // ===================== 列表 =====================
    @GetMapping("/page")
    public R<IPage<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(defaultValue = "pending") String tab,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) String batchCode) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        applyAuditScope(w, tab);             // 岗位阶段可见范围（待审=本岗/已审=已流过/所有=只读全量）
        if (projectId != null) w.eq("PROJECT_ID", projectId);
        if (batchCode != null && !batchCode.isBlank()) w.like("BATCH_CODE", batchCode);
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：审核岗只见本县/本乡镇批次
        w.orderByDesc("ID");

        Page<YktBatch> p = batchMapper.selectPage(new Page<>(pageNum, pageSize), w);

        // 富化：项目名、单位名。只查当页涉及的 id，不整表载入；HashMap 兜底避免 null key NPE
        Map<Long, String> projName = new HashMap<>();
        List<Long> pids = p.getRecords().stream().map(YktBatch::getProjectId).filter(Objects::nonNull).distinct().toList();
        if (!pids.isEmpty()) projectMapper.selectBatchIds(pids).forEach(pr -> projName.put(pr.getId(), pr.getProjectName()));
        Map<Long, String> orgName = new HashMap<>();
        List<Long> oids = p.getRecords().stream().map(YktBatch::getTownId).filter(Objects::nonNull).distinct().toList();
        if (!oids.isEmpty()) orgMapper.selectBatchIds(oids).forEach(o -> orgName.put(o.getId(), o.getOrgName()));

        List<Map<String, Object>> records = new ArrayList<>();
        for (YktBatch b : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("unitName", orgName.get(b.getTownId()));
            m.put("projectName", projName.get(b.getProjectId()));
            m.put("auditStage", b.getAuditStage());
            m.put("stageLabel", STAGE.getOrDefault(b.getAuditStage(), b.getAuditStage()));
            m.put("status", b.getLastResult());
            m.put("grantTime", b.getGrantTime());
            m.put("amount", b.getActualAmount() != null ? b.getActualAmount() : b.getPlanAmount());
            records.add(m);
        }
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(records);
        return R.ok(out);
    }

    // ===================== 流程进度（查看） =====================
    @GetMapping("/{id}/history")
    public R<List<YktAuditLog>> history(@PathVariable Long id) {
        assertScopeById(id);   // 县域隔离：流程进度也不给别县看
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<YktAuditLog>()
                .eq(YktAuditLog::getBatchId, id).orderByAsc(YktAuditLog::getSeqNo)));
    }

    @Data
    public static class AuditReq {
        private List<Long> ids;
        private String opinion;
    }

    // ===================== 审核（逐岗推进） =====================
    @PostMapping("/audit")
    @Transactional(rollbackFor = Exception.class)
    public R<?> audit(@RequestBody AuditReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("请选择要审核的批次");
        String opinion = (req.getOpinion() == null || req.getOpinion().isBlank()) ? "同意" : req.getOpinion();
        String myStage = myAuditStage();
        for (Long id : req.getIds()) {
            YktBatch b = batchMapper.selectById(id);
            if (b == null) continue;
            assertScope(b);                                               // 县域越权拦截
            requireStage(myStage, b.getAuditStage(), b.getBatchName());   // 岗位越权拦截
            String[] adv = ADVANCE.get(b.getAuditStage());
            if (adv == null) throw new BizException("批次[" + b.getBatchName() + "]已终审，无法继续审核");
            // 条件推进（并发防护）：0 行=已被并发审核/回退，报冲突而非重复推进
            int r = batchMapper.update(null, new UpdateWrapper<YktBatch>()
                    .eq("ID", b.getId()).eq("AUDIT_STAGE", b.getAuditStage())
                    .set("AUDIT_STAGE", adv[0]).set("LAST_RESULT", adv[1]));
            if (r == 0) throw new BizException("批次[" + b.getBatchName() + "]状态已变更，请刷新后重试");
            // 终审仅置「终审」态，不自动发送；批次发送(SENT)由「批次发送」菜单单独完成
            writeLog(b, "审核", adv[1], opinion, adv[0]);
        }
        return R.ok();
    }

    // ===================== 取消审核（逐岗回退一步） =====================
    @PostMapping("/cancel-audit")
    @Transactional(rollbackFor = Exception.class)
    public R<?> cancelAudit(@RequestBody AuditReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("请选择要取消审核的批次");
        String opinion = (req.getOpinion() == null || req.getOpinion().isBlank()) ? "撤销审核" : req.getOpinion();
        String myStage = myAuditStage();   // DRAFT=乡镇经办 / 各审核岗 / null=管理员 / ""=非审核链
        for (Long id : req.getIds()) {
            YktBatch b = batchMapper.selectById(id);
            if (b == null) continue;
            assertScope(b);                                               // 县域越权拦截
            if ("SENT".equals(b.getStatus()) || "PAID".equals(b.getStatus()))
                throw new BizException("批次[" + b.getBatchName() + "]已发送一体化，无法取消审核");
            int bi = CHAIN.indexOf(b.getAuditStage());
            if (bi <= 0) throw new BizException("批次[" + b.getBatchName() + "]当前无可取消的审核");
            // 撤回目标阶段：管理员=回退一步；本人=拉回自己经手的阶段（须是已流过本岗、即 mi<bi 的清册）
            String target;
            if (myStage == null) {
                target = CHAIN.get(bi - 1);
            } else {
                int mi = CHAIN.indexOf(myStage);
                if (mi < 0 || mi >= bi)
                    throw new BizException("批次[" + b.getBatchName() + "]不在您可撤回范围（仅能撤回已流过本岗、且仍在审批中的清册）");
                target = myStage;                 // 上游岗把清册拉回自己环节
            }
            // 条件回退（并发防护）：状态与阶段都要还停在读到的样子才生效
            UpdateWrapper<YktBatch> w = new UpdateWrapper<YktBatch>()
                    .eq("ID", b.getId()).eq("AUDIT_STAGE", b.getAuditStage()).eq("STATUS", b.getStatus())
                    .set("AUDIT_STAGE", target).set("LAST_RESULT", ARRIVED_TEXT.getOrDefault(target, target));
            if ("DRAFT".equals(target)) w.set("STATUS", "ISSUED");   // 撤回到录入=回已下达可编辑态，重进编制花名册
            if (batchMapper.update(null, w) == 0)
                throw new BizException("批次[" + b.getBatchName() + "]状态已变更，请刷新后重试");
            writeLog(b, "取消审核", "撤销审核", opinion, target);
        }
        return R.ok();
    }

    // ===================== 退回（退回乡镇经办岗重新编制花名册） =====================
    @PostMapping("/reject")
    @Transactional(rollbackFor = Exception.class)
    public R<?> reject(@RequestBody AuditReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("请选择要退回的批次");
        String opinion = (req.getOpinion() == null || req.getOpinion().isBlank()) ? "退回修改" : req.getOpinion();
        String myStage = myAuditStage();
        for (Long id : req.getIds()) {
            YktBatch b = batchMapper.selectById(id);
            if (b == null) continue;
            assertScope(b);                                               // 县域越权拦截
            requireStage(myStage, b.getAuditStage(), b.getBatchName());   // 只能退回落在本岗的批次
            // 仅审核中批次可退回；终审/已退回/未送审不可退回（终审撤销请用取消审核）
            if (!"SUBMITTED".equals(b.getStatus()) || "DRAFT".equals(b.getAuditStage()) || "DONE".equals(b.getAuditStage()))
                throw new BizException("批次[" + b.getBatchName() + "]非审核中状态，无法退回");
            String result = b.getAuditStage().startsWith("DEPT") ? "部门退回" : "乡镇退回";
            // 条件退回（并发防护）：退回乡镇经办岗=回已下达可编辑态，离开审核链，重编花名册后再送审
            int r = batchMapper.update(null, new UpdateWrapper<YktBatch>()
                    .eq("ID", b.getId()).eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", b.getAuditStage())
                    .set("STATUS", "ISSUED").set("AUDIT_STAGE", "DRAFT").set("LAST_RESULT", result));
            if (r == 0) throw new BizException("批次[" + b.getBatchName() + "]状态已变更，请刷新后重试");
            writeLog(b, "退回", result, opinion, "乡镇经办岗");
        }
        return R.ok();
    }

    // ===================== 补贴花名册 =====================
    @GetMapping("/{id}/rosters")
    public R<List<YktRoster>> rosters(@PathVariable Long id) {
        assertScopeById(id);   // 县域隔离
        return R.ok(rosterMapper.selectList(new LambdaQueryWrapper<YktRoster>().eq(YktRoster::getBatchId, id)));
    }

    // ===================== 校验银行账号 =====================
    @GetMapping("/{id}/check-bank")
    public R<Map<String, Object>> checkBank(@PathVariable Long id) {
        assertScopeById(id);   // 县域隔离
        List<YktRoster> rs = rosterMapper.selectList(new LambdaQueryWrapper<YktRoster>().eq(YktRoster::getBatchId, id));
        List<String> bad = new ArrayList<>();
        for (YktRoster r : rs) {
            if (r.getBeneficiaryId() == null) { bad.add(r.getName() + "：未关联补贴对象"); continue; }
            YktBeneficiary ben = beneficiaryMapper.selectById(r.getBeneficiaryId());
            if (ben == null || ben.getBankAccount() == null || ben.getBankAccount().isBlank()) {
                bad.add(r.getName() + "：银行账号缺失");
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", rs.size());
        m.put("invalid", bad.size());
        m.put("details", bad);
        return R.ok(m);
    }

    // ===================== 合计 =====================
    @GetMapping("/sum")
    public R<Map<String, Object>> sum(@RequestParam(defaultValue = "pending") String tab,
                                      @RequestParam(required = false) Long projectId) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        applyAuditScope(w, tab);             // 合计与列表口径完全一致
        if (projectId != null) w.eq("PROJECT_ID", projectId);
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：合计与列表口径一致
        // SQL 端求和（实发优先、否则计划），不再全表进内存
        w.select("NVL(SUM(COALESCE(ACTUAL_AMOUNT, PLAN_AMOUNT)),0) AS TOTAL");
        List<Object> objs = batchMapper.selectObjs(w);
        BigDecimal total = objs.isEmpty() || objs.get(0) == null
                ? BigDecimal.ZERO : new BigDecimal(String.valueOf(objs.get(0)));
        return R.ok(Map.of("totalAmount", total));
    }

    // ===================== 写流水 =====================
    private void writeLog(YktBatch b, String opType, String opResult, String opinion, String nextStage) {
        List<Object> mx = logMapper.selectObjs(new QueryWrapper<YktAuditLog>()
                .select("NVL(MAX(SEQ_NO),0)").eq("BATCH_ID", b.getId()));
        int maxSeq = mx.isEmpty() || mx.get(0) == null ? 0 : ((Number) mx.get(0)).intValue();
        YktAuditLog log = new YktAuditLog();
        log.setBatchId(b.getId());
        log.setSeqNo(maxSeq + 1);
        log.setDoneStation(STAGE.getOrDefault(b.getAuditStage(), b.getAuditStage()));
        log.setOperator(currentRealName());
        log.setOpType(opType);
        log.setOpResult(opResult);
        log.setOpinion(opinion);
        log.setOpTime(LocalDateTime.now());
        log.setPendingStation(STAGE.getOrDefault(nextStage, nextStage));
        logMapper.insert(log);
    }

    /** 岗位越权拦截：批次当前待审岗必须==本岗（管理员 myStage=null 不限；非审核岗 myStage="" 一律拒）。 */
    private void requireStage(String myStage, String batchStage, String batchName) {
        if (myStage == null) return; // 管理员
        if (myStage.isEmpty() || !myStage.equals(batchStage))
            throw new BizException("无权审核批次[" + batchName + "]：当前待「"
                    + STAGE.getOrDefault(batchStage, batchStage) + "」，非本岗职责");
    }

    private String currentRealName() {
        Long uid = UserContext.currentUserId();
        if (uid == null) return "系统";
        SysUser u = userMapper.selectById(uid);
        return u != null && u.getRealName() != null ? u.getRealName() : UserContext.currentUsername();
    }
}
