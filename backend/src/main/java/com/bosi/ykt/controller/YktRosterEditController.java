package com.bosi.ykt.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.controller.dto.GrantDetailExport;
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

    private Long tid() { return UserContext.currentTenantId(); }

    /** 写审核流水（与发放数据审核共用一张 YKT_AUDIT_LOG，保证「查看」流程进度完整） */
    private void writeAuditLog(Long batchId, String doneStation, String opType, String opResult,
                              String opinion, String pendingStation) {
        Long maxSeq = auditLogMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<YktAuditLog>()
                .eq(YktAuditLog::getBatchId, batchId));
        YktAuditLog log = new YktAuditLog();
        log.setBatchId(batchId);
        log.setSeqNo((maxSeq == null ? 0 : maxSeq.intValue()) + 1);
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

    /** 首页：待编制花名册列表（仅已下达 status=ISSUED；未下达批次不展示），带项目名 + 标签 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        // 已下达(可编制) + 刚送审待乡镇审核(SUBMITTED@TOWN_AUDIT，供锁定展示/取消送审)；
        // 过了乡镇审核的批次不再归编制岗，故不含更后阶段。
        w.and(x -> x.eq("STATUS", "ISSUED")
                .or(y -> y.eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", "TOWN_AUDIT")));
        w.orderByAsc("ID");
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：乡镇只见本乡镇待编制批次
        Map<Long, String> projName = new HashMap<>();
        projectMapper.selectList(null).forEach(p -> projName.put(p.getId(), p.getProjectName()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktBatch b : batchMapper.selectList(w)) {
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

    /** 批量填报-候选数据：数据来源 1=家庭成员表(补贴对象库) 2=历史发放数据 */
    @GetMapping("/fill-candidates")
    public R<List<Map<String, Object>>> fillCandidates(@RequestParam(defaultValue = "1") String source,
                                                        @RequestParam(required = false) Long villageId,
                                                        @RequestParam(required = false) String beneficiaryName) {
        Long t = tid();
        Map<Long, String> bankName = new HashMap<>();
        bankMapper.selectList(null).forEach(b -> bankName.put(b.getId(), b.getBankName()));
        Map<Long, String> villageName = new HashMap<>();
        villageMapper.selectList(null).forEach(v -> villageName.put(v.getId(), v.getVillageName()));

        List<Map<String, Object>> out = new ArrayList<>();
        if ("2".equals(source)) {
            // 历史发放数据：从已发放花名册去重取人
            QueryWrapper<YktGrantDetail> w = scope();
            if (beneficiaryName != null && !beneficiaryName.isBlank()) w.like("BENEFICIARY_NAME", beneficiaryName.trim());
            if (villageId != null) w.eq("VILLAGE_NAME", villageName.get(villageId));
            w.last("and rownum <= 500");
            Set<String> seen = new HashSet<>();
            for (YktGrantDetail d : grantMapper.selectList(w)) {
                String key = d.getBeneficiaryIdCard();
                if (key != null && !seen.add(key)) continue;
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
            w.last("and rownum <= 500");
            for (YktBeneficiary p : beneficiaryMapper.selectList(w)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("holderName", p.getHeadName());
                m.put("holderIdCard", p.getHeadIdCard());
                m.put("payeeName", p.getAccountName() != null ? p.getAccountName() : p.getName());
                m.put("payeeIdCard", p.getIdCard());
                m.put("bankAccount", p.getBankAccount());
                m.put("bankName", bankName.get(p.getBankId()));
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
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty()) return R.ok(0);
        YktBatch batch = batchMapper.selectById(batchId);
        int seq = nextSortNo(batchId);
        int n = 0;
        for (Map<String, Object> r : rows) {
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
            d.setFillDate(LocalDate.now());
            grantMapper.insert(d);
            n++;
        }
        refreshBatchTotals(batchId);
        return R.ok(n);
    }

    /** 新增/修改一条 */
    @PostMapping
    public R<Void> save(@RequestBody YktGrantDetail d) {
        if (d.getId() == null) {
            d.setSortNo(nextSortNo(d.getBatchId()));
            YktBatch b = batchMapper.selectById(d.getBatchId());
            if (b != null) d.setBatchCode(b.getBatchCode());
            if (d.getPayStatus() == null) d.setPayStatus("已申请");
            grantMapper.insert(d);
        } else {
            grantMapper.updateById(d);
        }
        return R.ok();
    }

    /** 批量修改（逐条 updateById） */
    @PostMapping("/batch-save")
    public R<Integer> batchSave(@RequestBody List<YktGrantDetail> list) {
        int n = 0;
        for (YktGrantDetail d : list) if (d.getId() != null) n += grantMapper.updateById(d);
        return R.ok(n);
    }

    /** 删除选中明细 */
    @DeleteMapping
    public R<Void> delete(@RequestParam String ids) {
        List<Long> idList = new ArrayList<>();
        for (String s : ids.split(",")) if (!s.isBlank()) idList.add(Long.valueOf(s.trim()));
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

        YktBatch batch = batchMapper.selectById(batchId);
        QueryWrapper<YktBeneficiary> bw = new QueryWrapper<>();
        Long t = tid();
        if (t != null) bw.eq("TENANT_ID", t);
        bw.eq("STATUS", "1");
        if (townId != null) bw.eq("TOWN_ID", townId);
        if (villageId != null) bw.eq("VILLAGE_ID", villageId);
        List<YktBeneficiary> people = beneficiaryMapper.selectList(bw);

        Map<Long, String> bankName = new HashMap<>();
        bankMapper.selectList(null).forEach(b -> bankName.put(b.getId(), b.getBankName()));
        Map<Long, String> villageName = new HashMap<>();
        villageMapper.selectList(null).forEach(v -> villageName.put(v.getId(), v.getVillageName()));

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

    /** 乡镇经办岗送审：补贴对象库一致性校验通过后 ISSUED -> SUBMITTED，进入乡镇审核岗(TOWN_AUDIT) */
    @PostMapping("/{batchId}/submit")
    public R<Void> submit(@PathVariable Long batchId) {
        validateForSubmit(batchId);          // 校验失败抛 BizException，停留花名册维护
        refreshBatchTotals(batchId);
        YktBatch b = new YktBatch();
        b.setId(batchId); b.setStatus("SUBMITTED"); b.setAuditStage("TOWN_AUDIT"); b.setLastResult("送审");
        batchMapper.updateById(b);
        // 流程进度：乡镇录入 --送审--> 乡镇审核
        writeAuditLog(batchId, "乡镇录入", "审核", "送审", "", "乡镇审核");
        return R.ok();
    }

    /**
     * 送审校验（对齐省厅流程「补贴送审校验」）：逐条与补贴对象库比对 享受人身份证/姓名/村组，
     * 校验失败汇总前 15 条抛出，乡镇修正后方可送审。
     * 注：社保卡校验项预留——当前补贴对象库社保卡数据为空，启用会误堵全部送审，待数据补齐后开启。
     */
    private void validateForSubmit(Long batchId) {
        List<YktGrantDetail> details = grantMapper.selectList(scope().eq("BATCH_ID", batchId));
        if (details.isEmpty()) throw new BizException("花名册为空，无法送审");

        Long t = tid();
        QueryWrapper<YktBeneficiary> bw = new QueryWrapper<>();
        if (t != null) bw.eq("TENANT_ID", t);
        bw.eq("STATUS", "1");
        Map<String, YktBeneficiary> lib = new HashMap<>();
        for (YktBeneficiary p : beneficiaryMapper.selectList(bw))
            if (p.getIdCard() != null) lib.put(p.getIdCard().trim(), p);
        Map<Long, String> villageName = new HashMap<>();
        villageMapper.selectList(null).forEach(v -> villageName.put(v.getId(), v.getVillageName()));

        List<String> errs = new ArrayList<>();
        int row = 0;
        for (YktGrantDetail d : details) {
            row++;
            String idc = d.getBeneficiaryIdCard() == null ? "" : d.getBeneficiaryIdCard().trim();
            String who = (d.getBeneficiaryName() == null || d.getBeneficiaryName().isBlank())
                    ? ("第" + row + "行") : d.getBeneficiaryName().trim();
            if (idc.isEmpty()) { errs.add("第" + row + "行 " + who + "：享受人身份证为空"); }
            else {
                YktBeneficiary p = lib.get(idc);
                if (p == null) { errs.add(who + "(" + idc + ")：不在补贴对象库"); }
                else {
                    String nm = d.getBeneficiaryName() == null ? "" : d.getBeneficiaryName().trim();
                    String libNm = p.getName() == null ? "" : p.getName().trim();
                    if (!nm.isEmpty() && !libNm.isEmpty() && !nm.equals(libNm))
                        errs.add(who + "(" + idc + ")：姓名与对象库不一致[应为" + libNm + "]");
                    String vn = d.getVillageName() == null ? "" : d.getVillageName().trim();
                    String libVn = p.getVillageId() == null ? "" : villageName.getOrDefault(p.getVillageId(), "").trim();
                    if (!vn.isEmpty() && !libVn.isEmpty() && !vn.equals(libVn))
                        errs.add(who + "(" + idc + ")：村组与对象库不一致[应为" + libVn + "]");
                }
            }
            if (errs.size() >= 15) break;
        }
        if (!errs.isEmpty())
            throw new BizException("送审校验未通过，请修正后再送审（前" + errs.size() + "条）：\n" + String.join("\n", errs));
    }

    /** 取消送审：SUBMITTED -> ISSUED（回到已下达可编辑态，而非未下达） */
    @PostMapping("/{batchId}/unsubmit")
    public R<Void> unsubmit(@PathVariable Long batchId) {
        YktBatch b = new YktBatch();
        b.setId(batchId); b.setStatus("ISSUED"); b.setAuditStage("DRAFT"); b.setLastResult("待编制");
        batchMapper.updateById(b);
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
            d.setPayStatus("已停发");
            d.setStopReason(req.getReason().trim());
            grantMapper.updateById(d);
            n++;
        }
        if (n == 0) throw new BizException("选中明细不存在");
        return R.ok(Map.of("count", n));
    }

    /** 删除批次（连同花名册）；更正发放批次由系统重构生成，禁止删除 */
    @DeleteMapping("/batch/{batchId}")
    public R<Void> deleteBatch(@PathVariable Long batchId) {
        YktBatch b = batchMapper.selectById(batchId);
        if (b != null && b.getBatchName() != null && b.getBatchName().startsWith("更正发放"))
            throw new BizException("更正发放批次由系统重构生成，不可删除");
        grantMapper.delete(scope().eq("BATCH_ID", batchId));
        batchMapper.deleteById(batchId);
        return R.ok();
    }

    /** 发放金额合计及批次备注信息 */
    @GetMapping("/{batchId}/summary")
    public R<Map<String, Object>> summary(@PathVariable Long batchId) {
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

    /** 导入花名册（Excel，列与导出一致） */
    @PostMapping("/import")
    public R<Integer> importExcel(@RequestParam Long batchId, @RequestParam("file") MultipartFile file) throws Exception {
        YktBatch batch = batchMapper.selectById(batchId);
        List<GrantDetailExport> rows = EasyExcel.read(file.getInputStream())
                .head(GrantDetailExport.class).sheet().doReadSync();
        int seq = nextSortNo(batchId);
        int n = 0;
        for (GrantDetailExport e : rows) {
            YktGrantDetail d = new YktGrantDetail();
            d.setBatchId(batchId);
            d.setBatchCode(batch == null ? null : batch.getBatchCode());
            d.setSortNo(seq++);
            d.setPayStatus(e.getPayStatus() != null ? e.getPayStatus() : "已申请");
            d.setHolderName(e.getHolderName());
            d.setHolderIdCard(e.getHolderIdCard());
            d.setPayeeName(e.getPayeeName());
            d.setPayeeIdCard(e.getPayeeIdCard());
            d.setBankAccount(e.getBankAccount());
            d.setBankName(e.getBankName());
            d.setVillageName(e.getVillageName());
            d.setGroupName(e.getGroupName());
            d.setBeneficiaryName(e.getBeneficiaryName());
            d.setBeneficiaryIdCard(e.getBeneficiaryIdCard());
            d.setPhone(e.getPhone());
            d.setResidence(e.getResidence());
            d.setAge(e.getAge());
            d.setAmount(e.getAmount());
            d.setFillDate(LocalDate.now());
            grantMapper.insert(d);
            n++;
        }
        refreshBatchTotals(batchId);
        return R.ok(n);
    }

    /** 下一个排序号 */
    private int nextSortNo(Long batchId) {
        Long cnt = grantMapper.selectCount(scope().eq("BATCH_ID", batchId));
        return (cnt == null ? 0 : cnt.intValue()) + 1;
    }

    /** 回填批次申请人数/金额（编制后批次自带统计） */
    private void refreshBatchTotals(Long batchId) {
        List<YktGrantDetail> list = grantMapper.selectList(scope().eq("BATCH_ID", batchId));
        BigDecimal total = BigDecimal.ZERO;
        for (YktGrantDetail d : list) if (d.getAmount() != null) total = total.add(d.getAmount());
        YktBatch b = new YktBatch();
        b.setId(batchId);
        b.setPlanCount(list.size());
        b.setPlanAmount(total);
        batchMapper.updateById(b);
    }
}
