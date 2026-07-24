package com.bosi.ykt.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.FormulaEngine;
import com.bosi.ykt.common.R;
import com.bosi.ykt.common.TplService;
import jakarta.servlet.http.HttpServletResponse;
import com.bosi.ykt.entity.*;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 编制花名册（系统管理 → 待编制花名册）。对生产「编制花名册」界面。
 * 数据落在 YKT_GRANT_DETAIL；批次状态：NEW 待编制 / SUBMITTED 已送审 / STOP 已停发。
 */
@RestController
@RequestMapping("/dept/roster")
@RequiredArgsConstructor
public class YktRosterEditController {

    private final YktGrantDetailMapper grantMapper;
    private final YktBatchMapper batchMapper;
    private final YktProjectMapper projectMapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    private final YktBankMapper bankMapper;
    private final YktVillageMapper villageMapper;
    private final YktAuditLogMapper auditLogMapper;
    private final SysUserMapper userMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;
    private final TplService tplService;

    private Long tid() { return UserContext.currentTenantId(); }

    /** 写审核流水（与发放数据审核共用一张 YKT_AUDIT_LOG，保证「查看」流程进度完整） */
    private void writeAuditLog(Long batchId, String doneStation, String opType, String opResult,
                              String opinion, String pendingStation) {
        // MAX+1 而非 count+1：流水一般不删，但口径统一防撞
        List<Object> mx = auditLogMapper.selectObjs(new QueryWrapper<YktAuditLog>()
                .select("NVL(MAX(SEQ_NO),0)").eq("BATCH_ID", batchId));
        int maxSeq = mx.isEmpty() || mx.get(0) == null ? 0 : ((Number) mx.get(0)).intValue();
        YktAuditLog log = new YktAuditLog();
        log.setBatchId(batchId);
        log.setSeqNo(maxSeq + 1);
        log.setDoneStation(doneStation);
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        log.setOperator(u != null && u.getRealName() != null ? u.getRealName() : UserContext.currentUsername());
        log.setOpType(opType);
        log.setOpResult(opResult);
        log.setOpinion(opinion);
        log.setOpTime(java.time.LocalDateTime.now());
        log.setPendingStation(pendingStation);
        auditLogMapper.insert(log);
    }

    private QueryWrapper<YktGrantDetail> scope() {
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        return w;
    }

    /** 县域越权兜底：委托 DataScopeResolver 单一真源。 */
    private void assertScope(Long batchId) {
        dataScope.assertBatch(batchId, "该批次");
    }

    /** 按明细 id 兜底：解析其所属批次后校验（供 updateById/删除等按明细操作的接口用）。 */
    private void assertDetailScope(Long detailId) {
        if (detailId == null) return;
        if (dataScope.allowedTowns() == null) return;
        YktGrantDetail d = grantMapper.selectById(detailId);
        if (d == null) throw new BizException("明细不存在");
        assertScope(d.getBatchId());
    }

    /** 状态机守卫：仅已下达(ISSUED)批次可编辑花名册（送审后/发送后/支付后一律拒，防直连 API 改已入账批次）。 */
    private void requireEditable(Long batchId) {
        YktBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        if (!"ISSUED".equals(b.getStatus()))
            throw new BizException("批次[" + b.getBatchName() + "]当前状态不可编辑花名册（仅已下达待编制批次可编辑）");
    }

    /** 明细级编辑守卫：由明细反查所属批次后走状态机守卫。 */
    private void requireDetailEditable(Long detailId) {
        YktGrantDetail d = detailId == null ? null : grantMapper.selectById(detailId);
        if (d == null) throw new BizException("明细不存在");
        requireEditable(d.getBatchId());
    }

    /** 首页：待编制花名册列表（仅已下达 status=ISSUED；未下达批次不展示），带项目名 + 标签。
     *  projectCode：补贴录入子项菜单(/entry/{code})按项目编码过滤本子项的批次。 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending(@RequestParam(required = false) String projectCode) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        if (projectCode != null && !projectCode.isBlank()) {
            // 菜单码为纯数字；白名单校验后才拼入子查询，防注入
            if (!projectCode.matches("\\d{1,12}")) throw new BizException("项目编码不合法");
            w.inSql("PROJECT_ID", "SELECT ID FROM YKT_PROJECT WHERE PROJECT_CODE='" + projectCode + "'");
        }
        // 已下达(可编制) + 刚送审待乡镇审核(SUBMITTED@TOWN_AUDIT，供锁定展示/取消送审)；
        // 过了乡镇审核的批次不再归编制岗，故不含更后阶段。
        w.and(x -> x.eq("STATUS", "ISSUED")
                .or(y -> y.eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", "TOWN_AUDIT")));
        w.orderByAsc("ID");
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：乡镇只见本乡镇待编制批次
        List<YktBatch> batches = batchMapper.selectList(w);
        // 只查结果集涉及的项目名，不整表载入
        Map<Long, String> projName = new HashMap<>();
        List<Long> pids = batches.stream().map(YktBatch::getProjectId).filter(Objects::nonNull).distinct().toList();
        if (!pids.isEmpty()) projectMapper.selectBatchIds(pids).forEach(p -> projName.put(p.getId(), p.getProjectName()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktBatch b : batches) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("projectName", projName.get(b.getProjectId()));
            m.put("status", b.getStatus());   // 前端据此锁定编辑按钮（SUBMITTED=已送审）
            m.put("tag", "SUBMITTED".equals(b.getStatus()) ? "已送审"
                    : (b.getRemark() == null || b.getRemark().isBlank() ? "新增" : b.getRemark()));
            out.add(m);
        }
        return R.ok(out);
    }

    /** 编制界面表头：批次基本信息 */
    @GetMapping("/{batchId}/info")
    public R<Map<String, Object>> info(@PathVariable Long batchId) {
        assertScope(batchId);
        YktBatch b = batchMapper.selectById(batchId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (b != null) {
            m.put("id", b.getId());
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("status", b.getStatus());
            m.put("remark", b.getRemark());
            m.put("townId", b.getTownId());
        }
        return R.ok(m);
    }

    private static void like(QueryWrapper<YktGrantDetail> w, String col, String v) {
        if (v != null && !v.isBlank()) w.like(col, v.trim());
    }

    /** 花名册明细分页（按批次 + 多字段筛选） */
    @GetMapping("/page")
    public R<IPage<YktGrantDetail>> page(@RequestParam Long batchId,
                                         @RequestParam(required = false) String holderName,
                                         @RequestParam(required = false) String holderIdCard,
                                         @RequestParam(required = false) String payeeName,
                                         @RequestParam(required = false) String payeeIdCard,
                                         @RequestParam(required = false) String bankAccount,
                                         @RequestParam(required = false) String beneficiaryName,
                                         @RequestParam(required = false) String beneficiaryIdCard,
                                         @RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "50") long pageSize) {
        QueryWrapper<YktGrantDetail> w = scope().eq("BATCH_ID", batchId);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离：越权 batchId 也取不到明细
        like(w, "HOLDER_NAME", holderName);
        like(w, "HOLDER_ID_CARD", holderIdCard);
        like(w, "PAYEE_NAME", payeeName);
        like(w, "PAYEE_ID_CARD", payeeIdCard);
        like(w, "BANK_ACCOUNT", bankAccount);
        like(w, "BENEFICIARY_NAME", beneficiaryName);
        like(w, "BENEFICIARY_ID_CARD", beneficiaryIdCard);
        w.orderByAsc("SORT_NO");
        return R.ok(grantMapper.selectPage(new Page<>(pageNum, pageSize), w));
    }

    /** 批量填报-候选数据：数据来源 1=补贴对象库 2=历史发放数据。
     *  batchId 传入时，已在本批次的享受人身份证不再作为候选（同批次人员不可重复）。 */
    @GetMapping("/fill-candidates")
    public R<List<Map<String, Object>>> fillCandidates(@RequestParam(defaultValue = "1") String source,
                                                        @RequestParam(required = false) Long batchId,
                                                        @RequestParam(required = false) Long villageId,
                                                        @RequestParam(required = false) String beneficiaryName,
                                                        @RequestParam(defaultValue = "HOUSEHOLD") String payeeMode) {
        Long t = tid();
        Map<Long, String> bankName = new HashMap<>();
        bankMapper.selectList(null).forEach(b -> bankName.put(b.getId(), b.getBankName()));
        // 同批次人员不可重复：已在本批次的享受人身份证集合，候选中剔除
        Set<String> existed = existingIdCards(batchId);

        List<Map<String, Object>> out = new ArrayList<>();
        if ("2".equals(source)) {
            // 历史发放数据：从已发放花名册去重取人
            QueryWrapper<YktGrantDetail> w = scope();
            dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离：历史发放候选只取本县
            if (beneficiaryName != null && !beneficiaryName.isBlank()) w.like("BENEFICIARY_NAME", beneficiaryName.trim());
            if (villageId != null) {
                YktVillage v = villageMapper.selectById(villageId);
                w.eq("VILLAGE_NAME", v == null ? null : v.getVillageName());
            }
            w.last("and rownum <= 500");
            Set<String> seen = new HashSet<>();
            for (YktGrantDetail d : grantMapper.selectList(w)) {
                String key = d.getBeneficiaryIdCard();
                if (key != null && !seen.add(key)) continue;
                if (key != null && existed.contains(key.trim())) continue;   // 已在本批次，跳过
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("holderName", d.getHolderName());
                m.put("holderIdCard", d.getHolderIdCard());
                m.put("payeeName", d.getPayeeName());
                m.put("payeeIdCard", d.getPayeeIdCard());
                m.put("bankAccount", d.getBankAccount());
                m.put("bankName", d.getBankName());
                m.put("villageName", d.getVillageName());
                m.put("groupName", d.getGroupName());
                m.put("beneficiaryName", d.getBeneficiaryName());
                m.put("beneficiaryIdCard", d.getBeneficiaryIdCard());
                m.put("phone", d.getPhone());
                m.put("age", d.getAge());
                out.add(m);
            }
        } else {
            // 家庭成员表：补贴对象库
            QueryWrapper<YktBeneficiary> w = new QueryWrapper<>();
            if (t != null) w.eq("TENANT_ID", t);
            w.eq("STATUS", "1");
            if (villageId != null) w.eq("VILLAGE_ID", villageId);
            if (beneficiaryName != null && !beneficiaryName.isBlank()) w.like("NAME", beneficiaryName.trim());
            dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：候选只取本县补贴对象
            w.last("and rownum <= 500");
            List<YktBeneficiary> people = beneficiaryMapper.selectList(w);
            Map<Long, String> villageName = villageNamesOf(people);
            // 收款人绑定：HOUSEHOLD=到户(默认，收款人=户主本人账户) / PERSON=到人(收款人=家庭成员本人账户)。
            // 到户时需按户主身份证载入户主档案取其账户（户主也是补贴对象里 relation=本人 的一行）。
            boolean toHousehold = !"PERSON".equals(payeeMode);
            Map<String, YktBeneficiary> headMap = new HashMap<>();
            if (toHousehold) {
                Set<String> headIdcs = new LinkedHashSet<>();
                for (YktBeneficiary p : people)
                    if (p.getHeadIdCard() != null && !p.getHeadIdCard().isBlank()) headIdcs.add(p.getHeadIdCard().trim());
                List<String> hl = new ArrayList<>(headIdcs);
                for (int i = 0; i < hl.size(); i += 1000) {
                    QueryWrapper<YktBeneficiary> hw = new QueryWrapper<>();
                    if (t != null) hw.eq("TENANT_ID", t);
                    hw.eq("STATUS", "1").in("ID_CARD", hl.subList(i, Math.min(i + 1000, hl.size())));
                    for (YktBeneficiary h : beneficiaryMapper.selectList(hw))
                        if (h.getIdCard() != null) headMap.putIfAbsent(h.getIdCard().trim(), h);
                }
            }
            for (YktBeneficiary p : people) {
                if (p.getIdCard() != null && existed.contains(p.getIdCard().trim())) continue;   // 已在本批次，跳过
                // 收款人账户来源：到户取户主档案（缺失则回落成员本人），到人取成员本人
                YktBeneficiary payer = p;
                if (toHousehold && p.getHeadIdCard() != null) {
                    YktBeneficiary h = headMap.get(p.getHeadIdCard().trim());
                    if (h != null) payer = h;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("holderName", p.getHeadName());
                m.put("holderIdCard", p.getHeadIdCard());
                m.put("payeeName", payer.getAccountName() != null && !payer.getAccountName().isBlank()
                        ? payer.getAccountName() : payer.getName());
                m.put("payeeIdCard", payer.getIdCard());
                m.put("bankAccount", payer.getBankAccount());
                m.put("bankName", bankName.get(payer.getBankId()));
                m.put("villageName", villageName.get(p.getVillageId()));
                m.put("groupName", p.getGroupName());
                m.put("beneficiaryName", p.getName());
                m.put("beneficiaryIdCard", p.getIdCard());
                m.put("phone", p.getPhone());
                m.put("age", p.getAge());
                out.add(m);
            }
        }
        return R.ok(out);
    }

    /** 批量填报-保存：勾选的候选+补贴金额 写入批次 */
    @PostMapping("/fill-save")
    @SuppressWarnings("unchecked")
    public R<Integer> fillSave(@RequestBody Map<String, Object> body) {
        Long batchId = Long.valueOf(String.valueOf(body.get("batchId")));
        assertScope(batchId);
        requireEditable(batchId);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty()) return R.ok(0);
        YktBatch batch = batchMapper.selectById(batchId);
        // 同批次人员不可重复：已在批次 + 本次已插入的享受人身份证都进 existed，命中即跳过
        Set<String> existed = existingIdCards(batchId);
        int seq = nextSortNo(batchId);
        int n = 0;
        for (Map<String, Object> r : rows) {
            String idc = r.get("beneficiaryIdCard") == null ? "" : String.valueOf(r.get("beneficiaryIdCard")).trim();
            if (!idc.isEmpty() && !existed.add(idc)) continue;   // 已存在，防重复添加
            YktGrantDetail d = new YktGrantDetail();
            d.setBatchId(batchId);
            d.setBatchCode(batch == null ? null : batch.getBatchCode());
            d.setSortNo(seq++);
            d.setPayStatus("已申请");
            d.setHolderName((String) r.get("holderName"));
            d.setHolderIdCard((String) r.get("holderIdCard"));
            d.setPayeeName((String) r.get("payeeName"));
            d.setPayeeIdCard((String) r.get("payeeIdCard"));
            d.setBankAccount((String) r.get("bankAccount"));
            d.setBankName((String) r.get("bankName"));
            d.setVillageName((String) r.get("villageName"));
            d.setGroupName((String) r.get("groupName"));
            d.setBeneficiaryName((String) r.get("beneficiaryName"));
            d.setBeneficiaryIdCard((String) r.get("beneficiaryIdCard"));
            d.setPhone((String) r.get("phone"));
            if (r.get("age") != null) d.setAge(Integer.valueOf(String.valueOf(r.get("age"))));
            if (r.get("standard") != null && !String.valueOf(r.get("standard")).isBlank())
                d.setStandard(new BigDecimal(String.valueOf(r.get("standard"))));
            if (r.get("amount") != null && !String.valueOf(r.get("amount")).isBlank())
                d.setAmount(new BigDecimal(String.valueOf(r.get("amount"))));
            d.setRemark(r.get("remark") == null ? null : String.valueOf(r.get("remark")));   // 非字符串强转会 ClassCastException，统一 toString
            try {   // 前端传畸形日期不应打挂整批保存，兜底当天
                d.setFillDate(r.get("fillDate") != null && !String.valueOf(r.get("fillDate")).isBlank()
                        ? LocalDate.parse(String.valueOf(r.get("fillDate"))) : LocalDate.now());
            } catch (java.time.format.DateTimeParseException ex) { d.setFillDate(LocalDate.now()); }
            grantMapper.insert(d);
            n++;
        }
        refreshBatchTotals(batchId);
        return R.ok(n);
    }

    /** 表单保存口径与导入一致：身份证必须 18 位（导入路径在模板校验里做，这里管住单条/批量修改） */
    private void checkIdCards(YktGrantDetail d) {
        checkIdCard("户主身份证号", d.getHolderIdCard());
        checkIdCard("收款人身份证号码", d.getPayeeIdCard());
        checkIdCard("享受人身份证号码", d.getBeneficiaryIdCard());
    }
    private void checkIdCard(String label, String v) {
        if (v != null && !v.isBlank() && v.trim().length() != 18)
            throw new BizException(label + "：" + v.trim() + " ，位数(" + v.trim().length() + ")错误，请核对！");
    }

    /** 新增/修改一条 */
    @PostMapping
    public R<Void> save(@RequestBody YktGrantDetail d) {
        checkIdCards(d);
        if (d.getId() == null) {
            assertScope(d.getBatchId());
            requireEditable(d.getBatchId());
            d.setSortNo(nextSortNo(d.getBatchId()));
            YktBatch b = batchMapper.selectById(d.getBatchId());
            if (b != null) d.setBatchCode(b.getBatchCode());
            if (d.getPayStatus() == null) d.setPayStatus("已申请");
            grantMapper.insert(d);
        } else {
            assertDetailScope(d.getId());
            requireDetailEditable(d.getId());
            grantMapper.updateById(d);
        }
        return R.ok();
    }

    /** 批量修改（逐条 updateById） */
    @PostMapping("/batch-save")
    public R<Integer> batchSave(@RequestBody List<YktGrantDetail> list) {
        int n = 0;
        for (YktGrantDetail d : list) if (d.getId() != null) { checkIdCards(d); assertDetailScope(d.getId()); requireDetailEditable(d.getId()); n += grantMapper.updateById(d); }
        return R.ok(n);
    }

    /** 删除选中明细 */
    @DeleteMapping
    public R<Void> delete(@RequestParam String ids) {
        List<Long> idList = new ArrayList<>();
        for (String s : ids.split(",")) if (!s.isBlank()) idList.add(Long.valueOf(s.trim()));
        for (Long id : idList) { assertDetailScope(id); requireDetailEditable(id); }   // 县域越权 + 状态机守卫
        if (!idList.isEmpty()) grantMapper.deleteBatchIds(idList);
        return R.ok();
    }

    /** 批量填报：从补贴对象库按 乡镇/村组 拉人进花名册 */
    @PostMapping("/batch-fill")
    public R<Integer> batchFill(@RequestBody Map<String, Object> body) {
        Long batchId = Long.valueOf(String.valueOf(body.get("batchId")));
        Long townId = body.get("townId") == null ? null : Long.valueOf(String.valueOf(body.get("townId")));
        Long villageId = body.get("villageId") == null ? null : Long.valueOf(String.valueOf(body.get("villageId")));
        BigDecimal standard = body.get("standard") == null ? null : new BigDecimal(String.valueOf(body.get("standard")));
        BigDecimal amount = body.get("amount") == null ? null : new BigDecimal(String.valueOf(body.get("amount")));

        assertScope(batchId);
        requireEditable(batchId);
        YktBatch batch = batchMapper.selectById(batchId);
        QueryWrapper<YktBeneficiary> bw = new QueryWrapper<>();
        Long t = tid();
        if (t != null) bw.eq("TENANT_ID", t);
        bw.eq("STATUS", "1");
        if (townId != null) bw.eq("TOWN_ID", townId);
        if (villageId != null) bw.eq("VILLAGE_ID", villageId);
        dataScope.applyTown(bw, "TOWN_ID");   // 县域隔离：只从本县补贴对象库拉人（防传别县 townId）
        List<YktBeneficiary> people = beneficiaryMapper.selectList(bw);

        Map<Long, String> bankName = new HashMap<>();
        bankMapper.selectList(null).forEach(b -> bankName.put(b.getId(), b.getBankName()));
        Map<Long, String> villageName = villageNamesOf(people);

        int seq = nextSortNo(batchId);
        int n = 0;
        for (YktBeneficiary p : people) {
            YktGrantDetail d = new YktGrantDetail();
            d.setBatchId(batchId);
            d.setBatchCode(batch == null ? null : batch.getBatchCode());
            d.setSortNo(seq++);
            d.setPayStatus("已申请");
            d.setHolderName(p.getHeadName());
            d.setHolderIdCard(p.getHeadIdCard());
            d.setPayeeName(p.getAccountName() != null ? p.getAccountName() : p.getName());
            d.setPayeeIdCard(p.getIdCard());
            d.setBankAccount(p.getBankAccount());
            d.setBankName(bankName.get(p.getBankId()));
            d.setVillageName(villageName.get(p.getVillageId()));
            d.setGroupName(p.getGroupName());
            d.setBeneficiaryName(p.getName());
            d.setBeneficiaryIdCard(p.getIdCard());
            d.setPhone(p.getPhone());
            d.setAge(p.getAge());
            d.setStandard(standard);
            d.setAmount(amount);
            d.setFillDate(LocalDate.now());
            d.setRelationship(p.getRelation());
            grantMapper.insert(d);
            n++;
        }
        refreshBatchTotals(batchId);
        return R.ok(n);
    }

    /**
     * 乡镇经办岗送审：补贴对象库一致性校验通过后 ISSUED -> SUBMITTED，进入乡镇审核岗(TOWN_AUDIT)。
     * 校验不过时返回 {ok:false, errors:[...]} 给前端「信息校验日志」弹窗展示，批次状态不动。
     */
    @PostMapping("/{batchId}/submit")
    public R<Map<String, Object>> submit(@PathVariable Long batchId) {
        assertScope(batchId);
        List<String> errs = validateForSubmit(batchId);
        if (!errs.isEmpty()) return R.ok(Map.of("ok", false, "errors", errs));
        refreshBatchTotals(batchId);
        // 条件更新：仅已下达(ISSUED)批次可送审，0 行=状态已变更（并发/越权直连）
        int r = batchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktBatch>()
                .eq("ID", batchId).eq("STATUS", "ISSUED")
                .set("STATUS", "SUBMITTED").set("AUDIT_STAGE", "TOWN_AUDIT").set("LAST_RESULT", "送审"));
        if (r == 0) throw new BizException("仅已下达待编制的批次可送审（状态已变更，请刷新）");
        // 流程进度：乡镇录入 --送审--> 乡镇审核
        writeAuditLog(batchId, "乡镇录入", "审核", "送审", "", "乡镇审核");
        return R.ok(Map.of("ok", true));
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static boolean same(String a, String b) { return nz(a).equals(nz(b)); }

    /**
     * 送审校验（对齐生产「信息校验日志」口径）：逐条按享受人身份证回查补贴对象库，比对三组信息——
     * ① 户主姓名/户主身份证号 ↔ 库 headName/headIdCard；
     * ② 收款人姓名/收款人身份证号/银行账号/开户银行 ↔ 库 accountName(缺省成员姓名)/idCard/bankAccount/bankId→名称；
     * ③ 享受人姓名+身份证 在库中存在且一致。
     * 村(居)民小组只要求非空不比对；全部一致方可送审。返回错误行（空=通过）。
     */
    private List<String> validateForSubmit(Long batchId) {
        QueryWrapper<YktGrantDetail> dw = scope().eq("BATCH_ID", batchId).orderByAsc("SORT_NO");
        List<YktGrantDetail> details = grantMapper.selectList(dw);
        if (details.isEmpty()) throw new BizException("花名册为空，无法送审");

        Long t = tid();
        // 只查本批次明细涉及的身份证（享受人 + 收款人；收款人可能是户主，账户核对要拿户主的库记录）（≤1000 一组分批 IN）
        Set<String> idcSet = new LinkedHashSet<>();
        for (YktGrantDetail d : details) {
            String bi = nz(d.getBeneficiaryIdCard()).trim();
            String pi = nz(d.getPayeeIdCard()).trim();
            if (!bi.isEmpty()) idcSet.add(bi);
            if (!pi.isEmpty()) idcSet.add(pi);
        }
        List<String> idcs = new ArrayList<>(idcSet);
        Map<String, YktBeneficiary> lib = new HashMap<>();
        for (int i = 0; i < idcs.size(); i += 1000) {
            QueryWrapper<YktBeneficiary> bw = new QueryWrapper<>();
            if (t != null) bw.eq("TENANT_ID", t);
            bw.eq("STATUS", "1").in("ID_CARD", idcs.subList(i, Math.min(i + 1000, idcs.size())));
            for (YktBeneficiary p : beneficiaryMapper.selectList(bw))
                if (p.getIdCard() != null) lib.put(p.getIdCard().trim(), p);
        }

        // 库存 bankId，清册存银行名称：只解析本批次涉及对象引用的 bankId（全国联行号库 1.7 万行，不整表载入）
        Map<Long, String> bankNames = new HashMap<>();
        List<Long> bids = lib.values().stream().map(YktBeneficiary::getBankId)
                .filter(Objects::nonNull).distinct().toList();
        if (!bids.isEmpty())
            for (YktBank b : bankMapper.selectBatchIds(bids)) bankNames.put(b.getId(), b.getBankName());

        // 同批次人员不可重复：统计每个享受人身份证的出现次数（导入可能带入重复，送审时拦截）
        Map<String, Integer> idcCount = new HashMap<>();
        for (YktGrantDetail d : details) {
            String idc = nz(d.getBeneficiaryIdCard());
            if (!idc.isEmpty()) idcCount.merge(idc, 1, Integer::sum);
        }

        List<String> errs = new ArrayList<>();
        for (YktGrantDetail d : details) {
            String idc = nz(d.getBeneficiaryIdCard());
            String name = nz(d.getBeneficiaryName());
            String head = "排序序号:" + (d.getSortNo() == null ? "" : d.getSortNo())
                    + " 享受人身份证号码：" + idc + " 享受人姓名：" + name + " ";
            // 身份证在本批次出现多次：逐行报「在本批次中重复」，不再往下比对基础库
            if (!idc.isEmpty() && idcCount.getOrDefault(idc, 0) > 1) {
                errs.add("排序序号:" + (d.getSortNo() == null ? "" : d.getSortNo())
                        + " 享受人身份证号码：" + idc + " 在本批次中重复！");
                if (errs.size() >= 500) break;
                continue;
            }
            if (nz(d.getGroupName()).isEmpty())
                errs.add(head + "村(居)民小组为空，请核对！");
            YktBeneficiary p = idc.isEmpty() ? null : lib.get(idc);
            if (p == null) {
                // 库里查无此人：户主/收款账户自然也对不上，按生产口径三条一起报
                errs.add(head + "对应的户主信息与补贴对象基础库信息不一致！");
                errs.add(head + "对应收款账户信息与补贴对象基础库信息不一致！");
                errs.add(head + "在补贴对象基础库信息缺失或者不一致！");
            } else {
                if (!same(d.getHolderName(), p.getHeadName()) || !same(d.getHolderIdCard(), p.getHeadIdCard()))
                    errs.add(head + "对应的户主信息与补贴对象基础库信息不一致！");
                // 收款人可为「享受人本人」或「本户户主」（补贴打到户主账户是合法场景）：
                // 账户须与「收款人本人」的补贴对象记录一致——本人比享受人记录，户主比户主记录，而不是一律死磕享受人。
                String payeeIdc = nz(d.getPayeeIdCard()).trim();
                boolean payeeIsSelf = !payeeIdc.isEmpty() && same(payeeIdc, p.getIdCard());
                boolean payeeIsHead = !payeeIdc.isEmpty() && same(payeeIdc, p.getHeadIdCard());
                YktBeneficiary payeeRef = payeeIsSelf ? p : (payeeIsHead ? lib.get(payeeIdc) : null);
                if (!payeeIsSelf && !payeeIsHead) {
                    errs.add(head + "收款人须为享受人本人或本户户主，请核对！");
                } else if (payeeRef == null) {
                    errs.add(head + "收款人（户主）在补贴对象基础库缺失或非正常状态，无法核对收款账户，请先维护户主档案！");
                } else {
                    String libPayee = payeeRef.getAccountName() != null && !payeeRef.getAccountName().isBlank()
                            ? payeeRef.getAccountName() : payeeRef.getName();
                    String libBank = payeeRef.getBankId() == null ? "" : nz(bankNames.get(payeeRef.getBankId()));
                    if (!same(d.getPayeeName(), libPayee) || !same(d.getPayeeIdCard(), payeeRef.getIdCard())
                            || !same(d.getBankAccount(), payeeRef.getBankAccount())
                            || !same(d.getBankName(), libBank))
                        errs.add(head + "对应收款账户信息与补贴对象基础库信息不一致！");
                }
                if (!same(name, p.getName()))
                    errs.add(head + "在补贴对象基础库信息缺失或者不一致！");
            }
            if (errs.size() >= 500) break;   // 护住弹窗与响应体，前 500 条足够定位
        }
        return errs;
    }

    /** 取消送审：SUBMITTED+TOWN_AUDIT -> ISSUED（仅刚送审、还没被乡镇审核经手的批次可撤回） */
    @PostMapping("/{batchId}/unsubmit")
    public R<Void> unsubmit(@PathVariable Long batchId) {
        assertScope(batchId);
        int r = batchMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktBatch>()
                .eq("ID", batchId).eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", "TOWN_AUDIT")
                .set("STATUS", "ISSUED").set("AUDIT_STAGE", "DRAFT").set("LAST_RESULT", "待编制"));
        if (r == 0) throw new BizException("仅刚送审（待乡镇审核）的批次可取消送审");
        return R.ok();
    }

    @lombok.Data public static class StopReq {
        private List<Long> detailIds;   // 选中的花名册明细（雪花，前端传字符串）
        private String reason;          // 停发原因（必填）
    }

    /**
     * 停发（明细级）：选中的花名册明细置「已停发」并记停发原因（必填）。
     * 停发明细不发起支付（不占额度），批次发送银行后归「退款」。清册整体仍可编辑。
     */
    @PostMapping("/stop-details")
    public R<?> stopDetails(@RequestBody StopReq req) {
        if (req == null || req.getDetailIds() == null || req.getDetailIds().isEmpty())
            throw new BizException("请选择要停发的明细");
        if (req.getReason() == null || req.getReason().isBlank())
            throw new BizException("请填写停发原因");
        int n = 0;
        for (Long id : req.getDetailIds()) {
            YktGrantDetail d = grantMapper.selectById(id);
            if (d == null) continue;
            assertScope(d.getBatchId());
            requireStoppable(d.getBatchId());   // 状态机守卫：发起支付(PAID)之后不可再停发，防资金核算错位
            d.setPayStatus("已停发");
            d.setStopReason(req.getReason().trim());
            grantMapper.updateById(d);
            n++;
        }
        if (n == 0) throw new BizException("选中明细不存在");
        return R.ok(Map.of("count", n));
    }

    /**
     * 停发守卫：批次进入支付环节(PAID/PAID_OUT)后不可再停发——
     * 支付申请金额在 gen 时已按「排除已停发」冻结，之后再停发会让指标核算与实际错位。
     */
    private void requireStoppable(Long batchId) {
        YktBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        if ("PAID".equals(b.getStatus()) || "PAID_OUT".equals(b.getStatus()))
            throw new BizException("批次[" + b.getBatchName() + "]已发起支付，不可再停发（请走更正发放）");
    }

    /** 删除批次（连同花名册）；仅未下达/已下达批次可删；更正发放批次由系统重构生成，禁止删除 */
    @DeleteMapping("/batch/{batchId}")
    public R<Void> deleteBatch(@PathVariable Long batchId) {
        assertScope(batchId);
        YktBatch b = batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        if (b.getBatchName() != null && b.getBatchName().startsWith("更正发放"))
            throw new BizException("更正发放批次由系统重构生成，不可删除");
        if (!"NEW".equals(b.getStatus()) && !"ISSUED".equals(b.getStatus()))
            throw new BizException("批次[" + b.getBatchName() + "]已进入审核/支付流程，不可删除");
        grantMapper.delete(scope().eq("BATCH_ID", batchId));
        batchMapper.deleteById(batchId);
        return R.ok();
    }

    /** 发放金额合计及批次备注信息 */
    @GetMapping("/{batchId}/summary")
    public R<Map<String, Object>> summary(@PathVariable Long batchId) {
        assertScope(batchId);
        List<YktGrantDetail> list = grantMapper.selectList(scope().eq("BATCH_ID", batchId));
        BigDecimal total = BigDecimal.ZERO;
        for (YktGrantDetail d : list) if (d.getAmount() != null) total = total.add(d.getAmount());
        YktBatch b = batchMapper.selectById(batchId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("personCount", list.size());
        m.put("totalAmount", total);
        m.put("batchName", b == null ? null : b.getBatchName());
        m.put("remark", b == null ? null : b.getRemark());
        m.put("status", b == null ? null : b.getStatus());
        return R.ok(m);
    }

    /** 导出清册导入模板（.xls，仅表头）。列来自项目的发放表定义（无自定义回落默认 18 列）。 */
    @GetMapping("/template")
    public void template(@RequestParam(required = false) Long batchId, HttpServletResponse resp) throws Exception {
        List<YktTplItem> cols;
        if (batchId != null) {
            YktBatch b = batchMapper.selectById(batchId);
            if (b == null) throw new BizException("批次不存在");
            cols = tplService.columnsOf(b.getProjectId());
        } else {
            cols = com.bosi.ykt.common.TplService.defaults();
        }
        resp.setContentType("application/vnd.ms-excel");
        resp.setCharacterEncoding("utf-8");
        String fileName = java.net.URLEncoder.encode("清册导入模板", java.nio.charset.StandardCharsets.UTF_8);
        resp.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xls");
        List<List<String>> head = cols.stream().map(c -> List.of(c.getItemLabel())).toList();
        EasyExcel.write(resp.getOutputStream()).head(head)
                .excelType(ExcelTypeEnum.XLS).sheet("清册").doWrite(List.of());
    }

    /**
     * 批次项目的模板列（绑定列 + 自由列，模板 SORT_NO 序），清册表格整表据此动态渲染：
     * 组内列顺序 = 排序号顺序（自由列可插到内置列之间）。sortNo/remark 绑定列不返回（网格有固定位置）。
     */
    @GetMapping("/{batchId}/tpl-cols")
    public R<List<Map<String, String>>> tplCols(@PathVariable Long batchId) {
        YktBatch b = batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        List<Map<String, String>> l = new ArrayList<>();
        for (YktTplItem c : tplService.columnsOf(b.getProjectId())) {
            String bind = nz(c.getBindField());
            String group = bind.isEmpty()
                    ? (nz(c.getColGroup()).isEmpty() ? "EXT" : c.getColGroup())
                    : TplService.BIND_GROUPS.get(bind);
            if (group == null) continue;
            l.add(Map.of("key", c.getItemKey(), "label", c.getItemLabel(), "group", group, "bind", bind));
        }
        return R.ok(l);
    }

    /** 金额/数值格式：数字、最多两位小数（负数/文本/千分位/三位小数一律不过） */
    private static final java.util.regex.Pattern AMOUNT_FMT = java.util.regex.Pattern.compile("^\\d{1,10}(\\.\\d{1,2})?$");
    /** 整数格式 */
    private static final java.util.regex.Pattern INT_FMT = java.util.regex.Pattern.compile("^\\d{1,10}$");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 清册导入（对齐生产「导入选项」）。列按项目发放表定义动态解析，前置校验任一条不过则整体不导入，
     * 错误按「信息校验日志」逐行返回：
     * ① 表头须与项目当前模板一致；② 必填列非空；③ 按列数据类型校验（身份证 18 位 / 整数 / 金额两位小数 / 日期）；
     * ④ 绑定 开户银行 的列须与系统银行设置全称一致；⑤ 绑定 村(居)委会 的列须与本乡镇村组维护全称一致。
     * 绑定明细字段的列落 YKT_GRANT_DETAIL 对应字段；未绑定的自由列（如 务工地点）校验后存 EXT_JSON。
     * 人员信息与补贴对象库的一致性仍留在送审阶段校验。
     */
    @PostMapping("/import")
    public R<Map<String, Object>> importExcel(@RequestParam Long batchId, @RequestParam("file") MultipartFile file) throws Exception {
        assertScope(batchId);
        requireEditable(batchId);
        YktBatch batch = batchMapper.selectById(batchId);
        List<YktTplItem> cols = tplService.columnsOf(batch.getProjectId());

        // headRowNumber(0)：首行为表头一并读入，值为 Map<列号, 单元格文本>
        List<Map<Integer, String>> raw = EasyExcel.read(file.getInputStream())
                .sheet().headRowNumber(0).doReadSync();
        if (raw.isEmpty()) throw new BizException("导入文件无有效数据行");

        // 表头须与项目当前模板逐列一致（防拿旧模板/别的项目模板导入错位）
        Map<Integer, String> hdr = raw.get(0);
        for (int i = 0; i < cols.size(); i++) {
            String h = nz(hdr.get(i));
            if (!h.equals(cols.get(i).getItemLabel()))
                return R.ok(Map.of("count", 0, "errors", List.of(
                        "导入文件表头与当前项目模板不一致（第" + (i + 1) + "列应为「" + cols.get(i).getItemLabel()
                                + "」，实际为「" + h + "」），请先导出最新模板后按模板填报！")));
        }

        // 剔除整行全空（Excel 尾部常见幽灵空行）；行号=在 raw 中的下标+1（表头占第 1 行）
        List<int[]> dataIdx = new ArrayList<>();   // [rawIndex]
        for (int i = 1; i < raw.size(); i++) {
            Map<Integer, String> row = raw.get(i);
            boolean blank = true;
            for (int c = 0; c < cols.size() && blank; c++)
                if (!nz(row.get(c)).isEmpty()) blank = false;
            if (!blank) dataIdx.add(new int[]{i});
        }
        if (dataIdx.isEmpty()) throw new BizException("导入文件无有效数据行");

        // 系统字典：开户银行全称 / 本批次乡镇的村(居)委会全称
        Set<String> bankNames = new HashSet<>();
        bankMapper.selectList(null).forEach(b -> { if (b.getBankName() != null) bankNames.add(b.getBankName().trim()); });
        QueryWrapper<YktVillage> vw = new QueryWrapper<>();
        if (batch != null && batch.getTownId() != null) vw.eq("TOWN_ID", batch.getTownId());
        Set<String> villageNames = new HashSet<>();
        villageMapper.selectList(vw).forEach(v -> { if (v.getVillageName() != null) villageNames.add(v.getVillageName().trim()); });

        // 公式列取操作数用：标识 -> 列号 / 列定义
        Map<String, Integer> idxByKey = new HashMap<>();
        Map<String, YktTplItem> colByKey = new HashMap<>();
        for (int c = 0; c < cols.size(); c++) {
            idxByKey.put(cols.get(c).getItemKey(), c);
            colByKey.put(cols.get(c).getItemKey(), cols.get(c));
        }

        List<String> errs = new ArrayList<>();
        for (int[] ix : dataIdx) {
            int rowNo = ix[0] + 1;   // Excel 显示行号
            Map<Integer, String> row = raw.get(ix[0]);
            for (int c = 0; c < cols.size(); c++) {
                YktTplItem col = cols.get(c);
                if (col.getFormula() != null && !col.getFormula().isEmpty()) {
                    // 公式列（清册样式公式）：单元格内容忽略，由系统按公式计算；此处校验该行能否算出
                    evalFormula(col, row, colByKey, idxByKey, rowNo, errs);
                    continue;
                }
                String v = nz(row.get(c));
                if (v.isEmpty()) {
                    if (Integer.valueOf(1).equals(col.getRequiredFlag()))
                        errs.add("Excel行号：" + rowNo + "，填报的数据中存在为空的必填项（" + col.getItemLabel() + "），请核对！");
                    continue;
                }
                switch (nz(col.getColType())) {
                    case "idcard" -> {
                        if (v.length() != 18)
                            errs.add("Excel行号：" + rowNo + " " + idcardLabel(col) + "：" + v + " ，位数(" + v.length() + ")错误，请核对！");
                    }
                    case "int" -> {
                        if (!INT_FMT.matcher(v).matches())
                            errs.add("Excel行号：" + rowNo + " " + col.getItemLabel() + "：" + v + " 必须为整数，请核对！");
                    }
                    case "decimal" -> {
                        if (!AMOUNT_FMT.matcher(v).matches())
                            errs.add("Excel行号：" + rowNo + " " + col.getItemLabel() + "：" + v + " 金额格式错误（须为数字，最多两位小数），请核对！");
                    }
                    case "date" -> {
                        if (parseDateOrNull(v) == null)
                            errs.add("Excel行号：" + rowNo + " " + col.getItemLabel() + "：" + v + " 日期格式错误（示例：2026-01-01），请核对！");
                    }
                    case "enum" -> {
                        // 枚举列（对生产“引用数据类型=枚举”）：值必须在发放表定义的允许值内
                        String ev = nz(col.getEnumValues());
                        if (!ev.isEmpty() && !Arrays.asList(ev.split(",")).contains(v))
                            errs.add("Excel行号：" + rowNo + " " + col.getItemLabel() + "：" + v
                                    + " 不在系统设置的允许值范围（" + ev.replace(",", "/") + "）内，请核对！");
                    }
                    default -> { }
                }
                String bind = nz(col.getBindField());
                if ("bankName".equals(bind) && !bankNames.contains(v))
                    errs.add("Excel行号：" + rowNo + " 开户银行名称：" + v + " 与系统设置的不符，请核对！");
                if ("villageName".equals(bind) && !villageNames.contains(v))
                    errs.add("Excel行号：" + rowNo + " 村(居)委会：" + v + " 与系统中村(居)委会的全称不符，请核对！");
            }
            if (errs.size() >= 500) break;
        }
        if (!errs.isEmpty()) return R.ok(Map.of("count", 0, "errors", errs));

        int seq = nextSortNo(batchId);
        int n = 0;
        for (int[] ix : dataIdx) {
            Map<Integer, String> row = raw.get(ix[0]);
            YktGrantDetail d = new YktGrantDetail();
            d.setBatchId(batchId);
            d.setBatchCode(batch == null ? null : batch.getBatchCode());
            d.setPayStatus("已申请");
            Map<String, String> ext = new LinkedHashMap<>();
            Integer sn = null;
            for (int c = 0; c < cols.size(); c++) {
                YktTplItem col = cols.get(c);
                if (col.getFormula() != null && !col.getFormula().isEmpty()) {
                    // 公式列按行计算（公式仅数值列可设，可绑定的只有 sortNo/standard/amount）
                    BigDecimal fv = evalFormula(col, row, colByKey, idxByKey, 0, null);
                    String fb = nz(col.getBindField());
                    if (fb.isEmpty()) {
                        if (fv != null) ext.put(col.getItemKey(), fv.toPlainString());
                    } else switch (fb) {
                        case "sortNo" -> sn = fv == null ? sn : fv.intValue();
                        case "standard" -> d.setStandard(fv);
                        case "amount" -> d.setAmount(fv);
                        default -> { }
                    }
                    continue;
                }
                String v = nz(row.get(c));
                String bind = nz(col.getBindField());
                if (bind.isEmpty()) {
                    if (!v.isEmpty()) ext.put(col.getItemKey(), v);
                    continue;
                }
                switch (bind) {
                    case "sortNo" -> sn = parseInt(v);
                    case "holderName" -> d.setHolderName(v);
                    case "holderIdCard" -> d.setHolderIdCard(v);
                    case "payeeName" -> d.setPayeeName(v);
                    case "payeeIdCard" -> d.setPayeeIdCard(v);
                    case "bankAccount" -> d.setBankAccount(v);
                    case "bankName" -> d.setBankName(v);
                    case "villageName" -> d.setVillageName(v);
                    case "groupName" -> d.setGroupName(v);
                    case "beneficiaryName" -> d.setBeneficiaryName(v);
                    case "beneficiaryIdCard" -> d.setBeneficiaryIdCard(v);
                    case "phone" -> d.setPhone(v);
                    case "standard" -> d.setStandard(parseMoney(v));
                    case "amount" -> d.setAmount(parseMoney(v));
                    case "fillDate" -> d.setFillDate(parseDate(v));
                    case "remark" -> d.setRemark(v);
                    default -> { }
                }
            }
            d.setSortNo(sn != null ? sn : seq++);
            if (d.getFillDate() == null) d.setFillDate(LocalDate.now());
            if (!ext.isEmpty()) d.setExtJson(JSON.writeValueAsString(ext));
            grantMapper.insert(d);
            n++;
        }
        refreshBatchTotals(batchId);
        return R.ok(Map.of("count", n, "errors", List.of()));
    }

    /**
     * 公式列按行取值：操作数取同一行其他列的单元格。操作数为空 → 返回 null（该公式列必填时写错误）；
     * 操作数格式错误由其自身列的类型校验报错，此处静默跳过防重复报。整数列四舍五入，金额列保留两位。
     */
    private static BigDecimal evalFormula(YktTplItem col, Map<Integer, String> row,
                                          Map<String, YktTplItem> colByKey, Map<String, Integer> idxByKey,
                                          int rowNo, List<String> errOut) {
        try {
            Map<String, BigDecimal> vars = new HashMap<>();
            for (String k : FormulaEngine.refs(col.getFormula())) {
                String v = nz(row.get(idxByKey.get(k)));
                if (v.isEmpty()) {
                    if (errOut != null && Integer.valueOf(1).equals(col.getRequiredFlag())) {
                        YktTplItem rc = colByKey.get(k);
                        errOut.add("Excel行号：" + rowNo + " " + col.getItemLabel() + " 按公式（" + col.getFormula()
                                + "）计算时「" + (rc == null ? k : rc.getItemLabel()) + "」为空，请核对！");
                    }
                    return null;
                }
                try { vars.put(k, new BigDecimal(v)); }
                catch (NumberFormatException e) { return null; }
            }
            BigDecimal r = FormulaEngine.eval(col.getFormula(), vars);
            return "int".equals(col.getColType())
                    ? r.setScale(0, java.math.RoundingMode.HALF_UP)
                    : r.setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            if (errOut != null)
                errOut.add("Excel行号：" + rowNo + " " + col.getItemLabel() + " 公式（" + col.getFormula()
                        + "）计算出错（除数为0），请核对！");
            return null;
        }
    }

    /** 身份证列错误文案标签：三个核心证件列沿用生产原文（收款人/享受人带“码”字），自由列用列名 */
    private static String idcardLabel(YktTplItem col) {
        return switch (nz(col.getBindField())) {
            case "holderIdCard" -> "户主身份证号";
            case "payeeIdCard" -> "收款人身份证号码";
            case "beneficiaryIdCard" -> "享受人身份证号码";
            default -> col.getItemLabel();
        };
    }

    private static Integer parseInt(String s) {
        try { return nz(s).isEmpty() ? null : new BigDecimal(nz(s)).intValueExact(); }
        catch (Exception ex) { return null; }
    }

    private static BigDecimal parseMoney(String s) {
        try { return nz(s).isEmpty() ? null : new BigDecimal(nz(s)); }
        catch (Exception ex) { return null; }
    }

    private static LocalDate parseDate(String s) {
        LocalDate d = parseDateOrNull(s);
        return d != null ? d : LocalDate.now();
    }

    /** 宽松归一（斜杠/年月日 → ISO）后解析，失败返回 null（供 date 列前置校验） */
    private static LocalDate parseDateOrNull(String s) {
        String v = nz(s).replace('/', '-').replace("年", "-").replace("月", "-").replace("日", "");
        if (v.isEmpty()) return null;
        try { return LocalDate.parse(v); } catch (Exception ex) { return null; }
    }

    /** 只查这批人涉及的村组名（≤1000 一组分批），不整表载入 */
    private Map<Long, String> villageNamesOf(List<YktBeneficiary> people) {
        Map<Long, String> villageName = new HashMap<>();
        List<Long> vids = people.stream().map(YktBeneficiary::getVillageId)
                .filter(Objects::nonNull).distinct().toList();
        for (int i = 0; i < vids.size(); i += 1000)
            villageMapper.selectBatchIds(vids.subList(i, Math.min(i + 1000, vids.size())))
                    .forEach(v -> villageName.put(v.getId(), v.getVillageName()));
        return villageName;
    }

    /** 本批次已存在的享受人身份证集合（供批量填报候选剔重 / 保存防重复）。batchId 为空返回空集。 */
    private Set<String> existingIdCards(Long batchId) {
        Set<String> s = new HashSet<>();
        if (batchId == null) return s;
        for (Object o : grantMapper.selectObjs(scope().select("BENEFICIARY_ID_CARD").eq("BATCH_ID", batchId)))
            if (o != null && !String.valueOf(o).trim().isEmpty()) s.add(String.valueOf(o).trim());
        return s;
    }

    /** 下一个排序号：MAX+1（count+1 在删过中间行后会与现存序号撞号） */
    private int nextSortNo(Long batchId) {
        List<Object> mx = grantMapper.selectObjs(scope().select("NVL(MAX(SORT_NO),0)").eq("BATCH_ID", batchId));
        int max = mx.isEmpty() || mx.get(0) == null ? 0 : ((Number) mx.get(0)).intValue();
        return max + 1;
    }

    /** 回填批次申请人数/金额（编制后批次自带统计）：SQL 端 COUNT/SUM，万人批次不再整表进内存 */
    private void refreshBatchTotals(Long batchId) {
        Map<String, Object> agg = grantMapper.selectMaps(scope()
                .select("COUNT(*) AS CNT", "NVL(SUM(AMOUNT),0) AS AMT").eq("BATCH_ID", batchId)).get(0);
        YktBatch b = new YktBatch();
        b.setId(batchId);
        b.setPlanCount(((Number) agg.get("CNT")).intValue());
        b.setPlanAmount(new BigDecimal(String.valueOf(agg.get("AMT"))));
        batchMapper.updateById(b);
    }
}
