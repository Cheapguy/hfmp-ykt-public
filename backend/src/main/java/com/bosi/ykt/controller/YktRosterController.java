package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktBatch;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.entity.YktRoster;
import com.bosi.ykt.mapper.YktBatchMapper;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import com.bosi.ykt.mapper.YktRosterMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 花名册维护。手册 §十一
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
    private final YktBatchMapper batchMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    /** 县域越权兜底：花名册经批次归乡镇，委托 DataScopeResolver 单一真源。 */
    private void assertBatchScope(Long batchId) {
        dataScope.assertBatch(batchId, "该花名册");
    }

    /** 按花名册 id 反查其批次乡镇后校验（防直连传别县 rosterId）。返回现存记录。 */
    private YktRoster assertRosterScope(Long id) {
        YktRoster r = id == null ? null : mapper.selectById(id);
        if (r == null) throw new BizException("花名册记录不存在");
        assertBatchScope(r.getBatchId());
        return r;
    }

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
        if (r.getId() == null) {
            // 新增：目标批次须在本县范围内，且不能替别县批次填报
            assertBatchScope(r.getBatchId());
            if (r.getAuditStatus() == null) r.setAuditStatus("DRAFT");
            if (r.getPayStatus() == null) r.setPayStatus("NONE");
            mapper.insert(r);
        } else {
            // 修改：以现存记录的归属批次判县域（防传本县 batchId 改别县 rosterId）；仅待编制可改
            YktRoster old = assertRosterScope(r.getId());
            if (!"DRAFT".equals(old.getAuditStatus()))
                throw new BizException("花名册已送审，待编制状态方可修改");
            r.setBatchId(old.getBatchId());   // 归属批次以库为准，禁止改挂到别的批次
            r.setAuditStatus(null); r.setPayStatus(null);   // 状态不经此接口改
            mapper.updateById(r);
        }
        return R.ok(r);
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        YktRoster old = assertRosterScope(id);
        if (!"DRAFT".equals(old.getAuditStatus()))
            throw new BizException("花名册已送审，待编制状态方可删除");
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

    /** 批量填报：从补贴对象库按村组拉人进花名册。手册 §十一(一)-4 */
    @PostMapping("/batch-fill")
    public R<Map<String, Object>> batchFill(@RequestBody BatchFillReq req) {
        if (req.getBatchId() == null) throw new BizException("缺少批次");
        assertBatchScope(req.getBatchId());   // 县域越权兜底：不能替别县批次批量填报
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

    /** 送审：校验与补贴对象库一致。手册 §十一(二) */
    @PostMapping("/{id}/submit")
    public R<?> submit(@PathVariable Long id) {
        YktRoster r = assertRosterScope(id);   // 县域越权兜底
        if (!"DRAFT".equals(r.getAuditStatus())) throw new BizException("仅待编制状态可送审");
        validate(r);
        r.setAuditStatus("TOWN_SUBMIT");
        mapper.updateById(r);
        return R.ok();
    }

    /** 审核：推进到下一审核阶段。手册 §十一(二) */
    @PostMapping("/{id}/audit")
    public R<?> audit(@PathVariable Long id) {
        YktRoster r = assertRosterScope(id);   // 县域越权兜底
        String next = NEXT.get(r.getAuditStatus());
        if (next == null) throw new BizException("当前状态无法继续审核");
        r.setAuditStatus(next);
        mapper.updateById(r);
        return R.ok();
    }

    /** 退回到待编制。手册 §十一(二) */
    @PostMapping("/{id}/return")
    public R<?> back(@PathVariable Long id) {
        YktRoster r = assertRosterScope(id);   // 县域越权兜底
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
