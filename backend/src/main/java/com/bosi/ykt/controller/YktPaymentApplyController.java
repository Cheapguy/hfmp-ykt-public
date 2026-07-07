package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.*;

/**
 * 一卡通发放申请。
 * 待支付 = 已发送一体化(SENT)的批次；发起支付按项目使用规则逐指标预扣减额度，生成支付申请，批次→已支付(PAID)。
 * 已支付可撤销支付：回滚指标额度、删除申请、批次回退待支付(SENT)。
 */
@RestController
@RequestMapping("/pay/apply")
@RequiredArgsConstructor
public class YktPaymentApplyController {

    private final YktPaymentApplyMapper mapper;
    private final YktPayApplyDetailMapper detailMapper;
    private final YktBatchMapper batchMapper;
    private final YktProjectMapper projectMapper;
    private final YktProjectQuotaMapper quotaMapper;
    private final YktIndicatorMapper indicatorMapper;
    private final YktGrantDetailMapper grantMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    private Long tid() { return UserContext.currentTenantId(); }

    /** 待支付批次（SENT） / 已支付批次（PAID 已发起支付、PAID_OUT 已银行代发） */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending(@RequestParam(required = false) Long projectId) {
        return R.ok(listBatches(projectId, "SENT"));
    }
    @GetMapping("/paid")
    public R<List<Map<String, Object>>> paid(@RequestParam(required = false) Long projectId) {
        return R.ok(listBatches(projectId, "PAID", "PAID_OUT"));
    }

    private List<Map<String, Object>> listBatches(Long projectId, String... statuses) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        w.in("STATUS", (Object[]) statuses);
        if (projectId != null) w.eq("PROJECT_ID", projectId);
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：财政只见本县待支付/已支付批次
        w.orderByDesc("ID");
        Map<Long, YktProject> projCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktBatch b : batchMapper.selectList(w)) {
            YktProject p = projCache.computeIfAbsent(b.getProjectId(), projectMapper::selectById);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("batchCode", b.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("projectId", b.getProjectId());
            m.put("projectCode", p == null ? null : p.getProjectCode());
            m.put("projectName", p == null ? null : p.getProjectName());
            m.put("planCount", b.getPlanCount());
            m.put("planAmount", b.getPlanAmount());
            m.put("status", b.getStatus());
            out.add(m);
        }
        return out;
    }

    /** 发起支付前预览：申请金额 + 按使用规则逐指标的本次预扣减金额（不落库） */
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam Long batchId) {
        assertScope(batchId);
        YktBatch b = batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        BigDecimal amount = applyAmount(b);
        List<Deduct> ded = computeDeduction(b.getProjectId(), amount);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("batchCode", b.getBatchCode());
        m.put("amount", amount);
        m.put("indicators", deductView(ded));
        return R.ok(m);
    }

    @Data public static class GenReq {
        private Long batchId; private String payee; private String usage;
    }

    /** 发起支付：扣减指标额度 + 生成支付申请，批次→已支付 */
    @PostMapping("/gen")
    @Transactional(rollbackFor = Exception.class)
    public R<?> gen(@RequestBody GenReq req) {
        assertScope(req.getBatchId());
        YktBatch b = batchMapper.selectById(req.getBatchId());
        if (b == null) throw new BizException("批次不存在");
        if (!"SENT".equals(b.getStatus())) throw new BizException("仅已发送一体化的批次可发起支付");

        BigDecimal amount = applyAmount(b);
        List<Deduct> ded = computeDeduction(b.getProjectId(), amount);   // 不足会抛异常

        YktPaymentApply a = new YktPaymentApply();
        a.setApplyNo(genApplyNo());
        a.setBatchId(b.getId());
        a.setProjectId(b.getProjectId());
        a.setAmount(amount);
        a.setPayee(req.getPayee());
        a.setUsage(req.getUsage());
        a.setStatus("PENDING_SUBMIT");      // 待送审
        mapper.insert(a);

        for (Deduct d : ded) {
            // 扣减指标：可用余额 -= 扣减，冻结 += 扣减
            YktIndicator ind = indicatorMapper.selectById(d.indicatorId);
            ind.setAvailableAmount(nz(ind.getAvailableAmount()).subtract(d.deduct));
            ind.setFrozenAmount(nz(ind.getFrozenAmount()).add(d.deduct));
            indicatorMapper.updateById(ind);
            YktPayApplyDetail pd = new YktPayApplyDetail();
            pd.setApplyId(a.getId());
            pd.setIndicatorId(d.indicatorId);
            pd.setIndicatorNo(d.indicatorNo);
            pd.setDeductAmount(d.deduct);
            detailMapper.insert(pd);
        }

        b.setStatus("PAID");
        b.setLastResult("已支付");
        batchMapper.updateById(b);
        // 花名册支付状态 → 已生成支付申请（已停发的不发起支付，保持「已停发」待银行环节退款）
        for (YktGrantDetail g : grantMapper.selectList(grantScope(b.getId()))) {
            if ("已停发".equals(g.getPayStatus())) continue;
            g.setPayStatus("已生成支付申请");
            grantMapper.updateById(g);
        }
        return R.ok(Map.of("applyId", a.getId(), "applyNo", a.getApplyNo()));
    }

    /** 撤销支付：回滚指标额度、删除申请明细，批次回退待支付 */
    @PostMapping("/revoke")
    @Transactional(rollbackFor = Exception.class)
    public R<?> revoke(@RequestBody Map<String, Object> body) {
        Long batchId = body.get("batchId") == null ? null : Long.valueOf(String.valueOf(body.get("batchId")));
        if (batchId == null) throw new BizException("缺少批次");
        assertScope(batchId);
        YktBatch b = batchMapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        if (!"PAID".equals(b.getStatus())) throw new BizException("仅已支付批次可撤销支付");

        for (YktPaymentApply a : mapper.selectList(new LambdaQueryWrapper<YktPaymentApply>()
                .eq(YktPaymentApply::getBatchId, batchId))) {
            if ("PAID".equals(a.getStatus())) throw new BizException("支付申请已支付完成，不可撤销");
            // 回滚指标额度
            for (YktPayApplyDetail d : detailMapper.selectList(new LambdaQueryWrapper<YktPayApplyDetail>()
                    .eq(YktPayApplyDetail::getApplyId, a.getId()))) {
                YktIndicator ind = indicatorMapper.selectById(d.getIndicatorId());
                if (ind != null) {
                    ind.setAvailableAmount(nz(ind.getAvailableAmount()).add(nz(d.getDeductAmount())));
                    ind.setFrozenAmount(nz(ind.getFrozenAmount()).subtract(nz(d.getDeductAmount())));
                    indicatorMapper.updateById(ind);
                }
                detailMapper.deleteById(d.getId());
            }
            mapper.deleteById(a.getId());
        }
        b.setStatus("SENT");
        b.setLastResult("已发送一体化");
        batchMapper.updateById(b);
        for (YktGrantDetail g : grantMapper.selectList(grantScope(batchId))) {
            g.setPayStatus("已申请");
            grantMapper.updateById(g);
        }
        return R.ok();
    }

    /** 某批次已生成的支付申请 + 指标扣减明细（已支付-查看详情） */
    @GetMapping("/{batchId}/detail")
    public R<Map<String, Object>> detail(@PathVariable Long batchId) {
        assertScope(batchId);
        List<YktPaymentApply> applies = mapper.selectList(new LambdaQueryWrapper<YktPaymentApply>()
                .eq(YktPaymentApply::getBatchId, batchId).orderByDesc(YktPaymentApply::getId));
        Map<String, Object> m = new LinkedHashMap<>();
        if (applies.isEmpty()) { m.put("apply", null); m.put("indicators", Collections.emptyList()); return R.ok(m); }
        YktPaymentApply a = applies.get(0);
        m.put("apply", a);
        List<Deduct> ded = new ArrayList<>();
        for (YktPayApplyDetail d : detailMapper.selectList(new LambdaQueryWrapper<YktPayApplyDetail>()
                .eq(YktPayApplyDetail::getApplyId, a.getId()))) {
            Deduct x = new Deduct();
            x.indicatorId = d.getIndicatorId(); x.indicatorNo = d.getIndicatorNo(); x.deduct = d.getDeductAmount();
            ded.add(x);
        }
        m.put("indicators", deductView(ded));

        // 发放结果：成功/失败(退回) 统计 + 失败明细
        int okCnt = 0, failCnt = 0;
        BigDecimal okAmt = BigDecimal.ZERO, failAmt = BigDecimal.ZERO;
        List<Map<String, Object>> fails = new ArrayList<>();
        for (YktGrantDetail d : grantMapper.selectList(grantScope(batchId))) {
            BigDecimal amt = nz(d.getAmount());
            if ("已支付".equals(d.getPayStatus())) { okCnt++; okAmt = okAmt.add(amt); }
            else if ("支付失败".equals(d.getPayStatus())) {
                failCnt++; failAmt = failAmt.add(amt);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", d.getBeneficiaryName() != null ? d.getBeneficiaryName() : d.getHolderName());
                f.put("bankAccount", d.getBankAccount());
                f.put("amount", amt);
                f.put("reason", d.getFailReason());
                fails.add(f);
            }
        }
        m.put("okCount", okCnt); m.put("okAmount", okAmt);
        m.put("failCount", failCnt); m.put("failAmount", failAmt);
        m.put("fails", fails);
        return R.ok(m);
    }

    /** 支付申请列表（支付申请录入用，按状态过滤） */
    @GetMapping("/page")
    public R<IPage<YktPaymentApply>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String status) {
        LambdaQueryWrapper<YktPaymentApply> w = new LambdaQueryWrapper<YktPaymentApply>()
                .eq(status != null && !status.isBlank(), YktPaymentApply::getStatus, status)
                .orderByDesc(YktPaymentApply::getId);
        Long t = tid();
        if (t != null) w.eq(YktPaymentApply::getTenantId, t);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离：经批次乡镇过滤
        return R.ok(mapper.selectPage(new Page<>(pageNum, pageSize), w));
    }

    // ========================= 支付申请录入（签章送审） =========================

    /** 待送审 / 已送审 支付申请列表（带批次、项目名富化） */
    @GetMapping("/submit-list")
    public R<List<Map<String, Object>>> submitList(@RequestParam(defaultValue = "PENDING_SUBMIT") String status) {
        LambdaQueryWrapper<YktPaymentApply> w = new LambdaQueryWrapper<YktPaymentApply>()
                .eq(YktPaymentApply::getStatus, status).orderByDesc(YktPaymentApply::getId);
        Long t = tid();
        if (t != null) w.eq(YktPaymentApply::getTenantId, t);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离：经批次乡镇过滤
        Map<Long, YktBatch> batchCache = new HashMap<>();
        Map<Long, YktProject> projCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktPaymentApply a : mapper.selectList(w)) {
            YktBatch b = batchCache.computeIfAbsent(a.getBatchId(), batchMapper::selectById);
            YktProject p = projCache.computeIfAbsent(a.getProjectId(), projectMapper::selectById);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("applyNo", a.getApplyNo());
            m.put("batchId", a.getBatchId());
            m.put("batchCode", b == null ? null : b.getBatchCode());
            m.put("batchName", b == null ? null : b.getBatchName());
            m.put("projectName", p == null ? null : p.getProjectName());
            m.put("amount", a.getAmount());
            m.put("payee", a.getPayee());
            m.put("usage", a.getUsage());
            m.put("status", a.getStatus());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 签章送审：待送审 → 已送审 */
    @PostMapping("/{id}/submit")
    @Transactional(rollbackFor = Exception.class)
    public R<?> submit(@PathVariable Long id) {
        YktPaymentApply a = mapper.selectById(id);
        if (a == null) throw new BizException("支付申请不存在");
        assertScope(a.getBatchId());
        if (!"PENDING_SUBMIT".equals(a.getStatus())) throw new BizException("仅待送审的申请可签章送审");
        a.setStatus("SUBMITTED");
        mapper.updateById(a);
        return R.ok();
    }

    /** 删除支付申请：回滚指标额度、删申请明细，批次退回终审（需一卡通重新发送一体化） */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<?> remove(@PathVariable Long id) {
        YktPaymentApply a = mapper.selectById(id);
        if (a == null) throw new BizException("支付申请不存在");
        assertScope(a.getBatchId());
        if ("PAID".equals(a.getStatus())) throw new BizException("已支付的申请不可删除");
        // 回滚指标额度
        for (YktPayApplyDetail d : detailMapper.selectList(new LambdaQueryWrapper<YktPayApplyDetail>()
                .eq(YktPayApplyDetail::getApplyId, a.getId()))) {
            YktIndicator ind = indicatorMapper.selectById(d.getIndicatorId());
            if (ind != null) {
                ind.setAvailableAmount(nz(ind.getAvailableAmount()).add(nz(d.getDeductAmount())));
                ind.setFrozenAmount(nz(ind.getFrozenAmount()).subtract(nz(d.getDeductAmount())));
                indicatorMapper.updateById(ind);
            }
            detailMapper.deleteById(d.getId());
        }
        mapper.deleteById(a.getId());
        // 批次退回终审，一卡通侧需重新「批次发送一体化」
        YktBatch b = batchMapper.selectById(a.getBatchId());
        if (b != null) {
            b.setStatus("SUBMITTED");
            b.setAuditStage("DONE");
            b.setLastResult("终审");
            batchMapper.updateById(b);
            for (YktGrantDetail g : grantMapper.selectList(grantScope(b.getId()))) {
                g.setPayStatus("已申请");
                grantMapper.updateById(g);
            }
        }
        return R.ok();
    }

    /** 银行代发待标记明细：列出该申请批次「已生成支付申请」的花名册，供人工标记成败 */
    @GetMapping("/{id}/grant-list")
    public R<List<Map<String, Object>>> grantList(@PathVariable Long id) {
        YktPaymentApply a = mapper.selectById(id);
        if (a == null) throw new BizException("支付申请不存在");
        assertScope(a.getBatchId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktGrantDetail d : grantMapper.selectList(grantScope(a.getBatchId()))) {
            if (!"已生成支付申请".equals(d.getPayStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getBeneficiaryName() != null ? d.getBeneficiaryName() : d.getHolderName());
            m.put("idCard", d.getBeneficiaryIdCard() != null ? d.getBeneficiaryIdCard() : d.getHolderIdCard());
            m.put("bankAccount", d.getBankAccount());
            m.put("bankName", d.getBankName());
            m.put("amount", d.getAmount());
            out.add(m);
        }
        return R.ok(out);
    }

    @Data public static class BankPayReq {
        private List<FailMark> fails;   // 人工标记为失败的明细；未列出的视为成功
    }
    @Data public static class FailMark {
        private Long detailId;
        private String reason;
    }

    /**
     * 模拟银行代发（集中支付收尾）：成败由经办人工标记（银行回单/人工核对），非系统自动判定。
     * 请求体 fails 中的明细 → 「支付失败」+回填原因，其余「已生成支付申请」明细 → 「已支付」。
     * 申请→PAID，批次→PAID_OUT，失败金额计入批次「退回金额」，失败明细可由主管「更正发放」重构二次发放。
     */
    @PostMapping("/{id}/bank-pay")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> bankPay(@PathVariable Long id, @RequestBody(required = false) BankPayReq req) {
        YktPaymentApply a = mapper.selectById(id);
        if (a == null) throw new BizException("支付申请不存在");
        assertScope(a.getBatchId());
        if (!"SUBMITTED".equals(a.getStatus())) throw new BizException("仅已送审的申请可进行银行代发");
        YktBatch b = batchMapper.selectById(a.getBatchId());
        if (b == null) throw new BizException("批次不存在");

        // 人工标记的失败明细 detailId → 原因
        Map<Long, String> failMap = new HashMap<>();
        if (req != null && req.getFails() != null)
            for (FailMark fm : req.getFails())
                if (fm.getDetailId() != null)
                    failMap.put(fm.getDetailId(), fm.getReason() == null || fm.getReason().isBlank() ? "银行代发失败" : fm.getReason());

        int okCnt = 0, failCnt = 0, stopCnt = 0;
        BigDecimal okAmt = BigDecimal.ZERO, failAmt = BigDecimal.ZERO, stopAmt = BigDecimal.ZERO;
        List<Map<String, Object>> fails = new ArrayList<>();
        for (YktGrantDetail d : grantMapper.selectList(grantScope(b.getId()))) {
            // 已停发明细：银行环节不支付，直接归退款（用原金额计入退款金额）
            if ("已停发".equals(d.getPayStatus())) { stopCnt++; stopAmt = stopAmt.add(nz(d.getAmount())); continue; }
            if (!"已生成支付申请".equals(d.getPayStatus())) continue;
            BigDecimal amt = nz(d.getAmount());
            if (failMap.containsKey(d.getId())) {
                String reason = failMap.get(d.getId());
                d.setPayStatus("支付失败");
                d.setFailReason(reason);
                failCnt++; failAmt = failAmt.add(amt);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", d.getBeneficiaryName() != null ? d.getBeneficiaryName() : d.getHolderName());
                f.put("bankAccount", d.getBankAccount());
                f.put("amount", amt);
                f.put("reason", reason);
                fails.add(f);
            } else {
                d.setPayStatus("已支付");
                d.setFailReason(null);
                okCnt++; okAmt = okAmt.add(amt);
            }
            grantMapper.updateById(d);
        }
        if (okCnt == 0 && failCnt == 0 && stopCnt == 0) throw new BizException("该批次无可代发明细（须为已生成支付申请）");

        // 指标核算：解冻全部，成功额转「已支付」，失败额退回「可用」（钱守恒）
        BigDecimal failRemain = failAmt;
        for (YktPayApplyDetail d : detailMapper.selectList(new LambdaQueryWrapper<YktPayApplyDetail>()
                .eq(YktPayApplyDetail::getApplyId, a.getId()))) {
            YktIndicator ind = indicatorMapper.selectById(d.getIndicatorId());
            if (ind == null) continue;
            BigDecimal dd = nz(d.getDeductAmount());
            BigDecimal back = failRemain.min(dd);          // 本指标退回可用的部分
            BigDecimal paidPart = dd.subtract(back);       // 本指标实付的部分
            ind.setFrozenAmount(nz(ind.getFrozenAmount()).subtract(dd));
            ind.setAvailableAmount(nz(ind.getAvailableAmount()).add(back));
            ind.setPaidAmount(nz(ind.getPaidAmount()).add(paidPart));
            indicatorMapper.updateById(ind);
            failRemain = failRemain.subtract(back);
        }

        // 申请→已支付；批次→已发放，回填：实发=成功 / 退回=银行失败 / 退款=停发
        a.setStatus("PAID");
        mapper.updateById(a);
        b.setStatus("PAID_OUT");
        b.setActualCount(okCnt);
        b.setActualAmount(okAmt);
        b.setReturnAmount(failAmt);
        b.setRefundAmount(stopAmt);
        b.setGrantTime(java.time.LocalDateTime.now());
        StringBuilder lr = new StringBuilder(okCnt > 0 ? "已发放" : "未发放");
        if (failCnt > 0) lr.append("·退回").append(failCnt).append("人");
        if (stopCnt > 0) lr.append("·停发").append(stopCnt).append("人");
        b.setLastResult(lr.toString());
        batchMapper.updateById(b);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("okCount", okCnt); r.put("okAmount", okAmt);
        r.put("failCount", failCnt); r.put("failAmount", failAmt);
        r.put("stopCount", stopCnt); r.put("stopAmount", stopAmt);
        r.put("fails", fails);
        return R.ok(r);
    }

    // ===== 内部 =====

    /** 申请金额：汇总花名册（排除已停发——停发的不发起支付，走退款） */
    private BigDecimal applyAmount(YktBatch b) {
        BigDecimal t = BigDecimal.ZERO;
        for (YktGrantDetail g : grantMapper.selectList(grantScope(b.getId())))
            if (g.getAmount() != null && !"已停发".equals(g.getPayStatus())) t = t.add(g.getAmount());
        return t;
    }

    static class Deduct { Long indicatorId; String indicatorNo; YktIndicator ind; BigDecimal deduct; int priority; }

    /** 按项目使用规则逐指标计算预扣减；额度不足抛异常 */
    private List<Deduct> computeDeduction(Long projectId, BigDecimal amount) {
        List<YktProjectQuota> quotas = quotaMapper.selectList(new LambdaQueryWrapper<YktProjectQuota>()
                .eq(YktProjectQuota::getProjectId, projectId));
        if (quotas.isEmpty()) throw new BizException("该项目未挂接任何指标，无法发起支付");
        String rule = quotas.stream().map(YktProjectQuota::getUseRule).filter(Objects::nonNull).findFirst().orElse("PRIORITY");

        // 关联指标
        List<Deduct> list = new ArrayList<>();
        for (YktProjectQuota q : quotas) {
            YktIndicator ind = q.getIndicatorId() == null ? null : indicatorMapper.selectById(q.getIndicatorId());
            if (ind == null) continue;
            Deduct d = new Deduct();
            d.indicatorId = ind.getId(); d.indicatorNo = ind.getIndicatorNo(); d.ind = ind;
            d.deduct = BigDecimal.ZERO;
            d.priority = q.getPriority() == null ? Integer.MAX_VALUE : q.getPriority();
            list.add(d);
        }
        // 排序：PRIORITY 按优先级升序；ASC 可用余额升序；DESC 可用余额降序
        if ("ASC".equals(rule)) list.sort(Comparator.comparing(d -> nz(d.ind.getAvailableAmount())));
        else if ("DESC".equals(rule)) list.sort((a, c) -> nz(c.ind.getAvailableAmount()).compareTo(nz(a.ind.getAvailableAmount())));
        else list.sort(Comparator.comparingInt(d -> d.priority));

        BigDecimal remain = amount;
        for (Deduct d : list) {
            if (remain.signum() <= 0) break;
            BigDecimal avail = nz(d.ind.getAvailableAmount());
            if (avail.signum() <= 0) continue;
            BigDecimal use = avail.min(remain);
            d.deduct = use;
            remain = remain.subtract(use);
        }
        if (remain.signum() > 0)
            throw new BizException("指标可用余额不足，尚缺 " + remain.toPlainString() + " 元，请先调整指标或额度挂接");
        list.removeIf(d -> d.deduct.signum() <= 0);   // 只保留实际扣减的
        return list;
    }

    private List<Map<String, Object>> deductView(List<Deduct> ded) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Deduct d : ded) {
            YktIndicator ind = d.ind != null ? d.ind : indicatorMapper.selectById(d.indicatorId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("indicatorNo", d.indicatorNo);
            m.put("govEconCode", ind == null ? null : ind.getGovEconCode());
            m.put("govEconName", ind == null ? null : ind.getGovEconName());
            m.put("deptEconCode", ind == null ? null : ind.getDeptEconCode());
            m.put("deptEconName", ind == null ? null : ind.getDeptEconName());
            m.put("budgetProject", ind == null ? null : ind.getBudgetProject());
            m.put("availableAmount", ind == null ? null : ind.getAvailableAmount());
            m.put("deductAmount", d.deduct);
            out.add(m);
        }
        return out;
    }

    /** 县域越权兜底：批次乡镇不在本人可见范围则拒（管理员/全部 allowedTowns=null 放行）。 */
    private void assertScope(Long batchId) {
        Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        YktBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        if (b == null || b.getTownId() == null || !towns.contains(b.getTownId()))
            throw new BizException("无权操作该批次（非本县数据）");
    }

    private QueryWrapper<YktGrantDetail> grantScope(Long batchId) {
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        Long t = tid();
        if (t != null) w.eq("TENANT_ID", t);
        w.eq("BATCH_ID", batchId);
        return w;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private String genApplyNo() { return "PA" + System.currentTimeMillis(); }
}
