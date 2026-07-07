package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.entity.YktRoster;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import com.bosi.ykt.mapper.YktRosterMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 花名册维护。
 * 审核流：DRAFT --乡镇送审--> TOWN_SUBMIT --乡镇审核--> TOWN_AUDIT
 *         --部门送审--> DEPT_SUBMIT --部门审核(终审)--> FINAL
 * 送审时与补贴对象库按 姓名/身份证/社保卡/村组 校验。
 */
@RestController
@RequestMapping("/roster")
@RequiredArgsConstructor
public class YktRosterController {

    private final YktRosterMapper mapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    /** 审核状态推进次序 */
    private static final Map<String, String> NEXT = Map.of(
            "DRAFT", "TOWN_SUBMIT",
            "TOWN_SUBMIT", "TOWN_AUDIT",
            "TOWN_AUDIT", "DEPT_SUBMIT",
            "DEPT_SUBMIT", "FINAL"
    );

    @GetMapping("/page")
    public R<IPage<YktRoster>> page(@RequestParam(defaultValue = "1") long pageNum,
                                    @RequestParam(defaultValue = "10") long pageSize,
                                    @RequestParam(required = false) Long batchId,
                                    @RequestParam(required = false) String auditStatus) {
        LambdaQueryWrapper<YktRoster> w = new LambdaQueryWrapper<YktRoster>()
                .eq(batchId != null, YktRoster::getBatchId, batchId)
                .eq(auditStatus != null && !auditStatus.isBlank(), YktRoster::getAuditStatus, auditStatus)
                .orderByDesc(YktRoster::getId);
        dataScope.applyBatchTown(w, "BATCH_ID");   // 县域隔离（花名册经批次归乡镇）
        return R.ok(mapper.selectPage(new Page<>(pageNum, pageSize), w));
    }

    @PostMapping
    public R<?> save(@RequestBody YktRoster r) {
        if (r.getAuditStatus() == null) r.setAuditStatus("DRAFT");
        if (r.getPayStatus() == null) r.setPayStatus("NONE");
        if (r.getId() == null) mapper.insert(r); else mapper.updateById(r);
        return R.ok(r);
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    @Data
    public static class BatchFillReq {
        private Long batchId;
        private Long townId;
        private Long villageId;
        private BigDecimal standard;
        private BigDecimal amount;
    }

    /** 批量填报：从补贴对象库按村组拉人进花名册。*/
    @PostMapping("/batch-fill")
    public R<Map<String, Object>> batchFill(@RequestBody BatchFillReq req) {
        if (req.getBatchId() == null) throw new BizException("缺少批次");
        List<YktBeneficiary> people = beneficiaryMapper.selectList(
                new LambdaQueryWrapper<YktBeneficiary>()
                        .eq(req.getTownId() != null, YktBeneficiary::getTownId, req.getTownId())
                        .eq(req.getVillageId() != null, YktBeneficiary::getVillageId, req.getVillageId())
                        .eq(YktBeneficiary::getStatus, "1"));
        int n = 0;
        for (YktBeneficiary b : people) {
            YktRoster r = new YktRoster();
            r.setBatchId(req.getBatchId());
            r.setBeneficiaryId(b.getId());
            r.setName(b.getName());
            r.setIdCard(b.getIdCard());
            r.setSocialCard(b.getSocialCard());
            r.setVillageId(b.getVillageId());
            r.setStandard(req.getStandard());
            r.setAmount(req.getAmount());
            r.setAuditStatus("DRAFT");
            r.setPayStatus("NONE");
            mapper.insert(r);
            n++;
        }
        return R.ok(Map.of("filled", n));
    }

    /** 送审：校验与补贴对象库一致。*/
    @PostMapping("/{id}/submit")
    public R<?> submit(@PathVariable Long id) {
        YktRoster r = mapper.selectById(id);
        if (r == null) throw new BizException("花名册记录不存在");
        if (!"DRAFT".equals(r.getAuditStatus())) throw new BizException("仅待编制状态可送审");
        validate(r);
        r.setAuditStatus("TOWN_SUBMIT");
        mapper.updateById(r);
        return R.ok();
    }

    /** 审核：推进到下一审核阶段。*/
    @PostMapping("/{id}/audit")
    public R<?> audit(@PathVariable Long id) {
        YktRoster r = mapper.selectById(id);
        if (r == null) throw new BizException("花名册记录不存在");
        String next = NEXT.get(r.getAuditStatus());
        if (next == null) throw new BizException("当前状态无法继续审核");
        r.setAuditStatus(next);
        mapper.updateById(r);
        return R.ok();
    }

    /** 退回到待编制。*/
    @PostMapping("/{id}/return")
    public R<?> back(@PathVariable Long id) {
        YktRoster r = mapper.selectById(id);
        if (r == null) throw new BizException("花名册记录不存在");
        r.setAuditStatus("DRAFT");
        mapper.updateById(r);
        return R.ok();
    }

    private void validate(YktRoster r) {
        if (r.getBeneficiaryId() == null) throw new BizException("未关联补贴对象，校验失败");
        YktBeneficiary b = beneficiaryMapper.selectById(r.getBeneficiaryId());
        if (b == null) throw new BizException("补贴对象不存在，校验失败");
        if (!eq(b.getName(), r.getName()) || !eq(b.getIdCard(), r.getIdCard())
                || !eq(b.getSocialCard(), r.getSocialCard())
                || !java.util.Objects.equals(b.getVillageId(), r.getVillageId())) {
            throw new BizException("与补贴对象库信息不一致（姓名/身份证/社保卡/村组），校验失败");
        }
    }

    private boolean eq(String a, String b) { return java.util.Objects.equals(a, b); }
}
