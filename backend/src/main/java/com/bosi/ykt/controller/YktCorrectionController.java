package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.*;
import com.bosi.ykt.mapper.*;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 更正发放（主管部门）。手册 §十一(八)。
 *
 * 支付时出现卡号错误或需退款 → 数据被一体化置「已退款/已退回」。本界面列出这些数据，
 * 点【批次重构】按 项目+乡镇 生成新的「已下达」批次并把人员复制进去，由乡镇重新填报花名册送审。
 */
@RestController
@RequestMapping("/dept/correction")
@RequiredArgsConstructor
public class YktCorrectionController {

    private static final List<String> CORRECTABLE = Arrays.asList("已退款", "已退回", "支付失败");

    private final YktGrantDetailMapper grantMapper;
    private final YktBatchMapper batchMapper;
    private final SysOrgMapper orgMapper;
    private final YktAuditLogMapper auditLogMapper;
    private final SysUserMapper userMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    /** 待更正列表：退款/退回的花名册（可按 项目 / 批次 / 乡镇 过滤） */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) String batchCode,
                                             @RequestParam(required = false) Long townId) {
        Long tid = UserContext.currentTenantId();

        // 支付状态 + 项目/批次/乡镇/县域 全部下推 SQL（项目/乡镇用子查询，不拼超长 IN 防 ORA-01795），
        // Java 侧只做富化；ROWNUM 兜底封顶防结果集失控（正常退款集远小于此）。
        QueryWrapper<YktGrantDetail> w = new QueryWrapper<>();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.in("PAY_STATUS", CORRECTABLE);
        if (projectId != null) w.inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE PROJECT_ID = " + projectId);
        if (townId != null) w.inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE TOWN_ID = " + townId);
        String code = batchCode == null ? null : batchCode.trim();
        if (code != null && !code.isEmpty()) w.like("BATCH_CODE", code);
        dataScope.applyBatchTown(w, "BATCH_ID");        // 县域隔离下推 SQL
        w.last("AND ROWNUM <= 5000 ORDER BY BATCH_ID, SORT_NO");
        List<YktGrantDetail> details = grantMapper.selectList(w);
        if (details.isEmpty()) return R.ok(Collections.emptyList());

        Map<Long, YktBatch> batchCache = new HashMap<>();
        // 单位名按命中批次的乡镇 id 惰性查，不整表载入
        Map<Long, String> orgName = new HashMap<>();

        List<Map<String, Object>> out = new ArrayList<>();
        for (YktGrantDetail d : details) {
            YktBatch b = batchCache.computeIfAbsent(d.getBatchId(), batchMapper::selectById);
            if (b == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("beneficiaryName", d.getBeneficiaryName());
            m.put("bankAccount", d.getBankAccount());
            m.put("batchCode", d.getBatchCode());
            m.put("batchName", b.getBatchName());
            m.put("projectId", b.getProjectId());   // 供工作台待办点入回填项目下拉
            m.put("townName", b.getTownId() == null ? null : orgName.computeIfAbsent(b.getTownId(),
                    id -> { SysOrg o = orgMapper.selectById(id); return o == null ? null : o.getOrgName(); }));
            m.put("villageName", d.getVillageName());
            m.put("groupName", d.getGroupName());
            m.put("amount", d.getAmount());
            m.put("payStatus", d.getPayStatus());
            m.put("failReason", d.getFailReason());
            m.put("retryTimes", d.getRetryTimes() == null ? 0 : d.getRetryTimes());
            m.put("remark", d.getRemark());
            out.add(m);
        }
        return R.ok(out);
    }

    @Data
    public static class RebuildReq {
        private List<Long> detailIds;   // 选中的退款/退回花名册明细（雪花，前端传字符串）
    }

    /** 批次重构：按 项目+乡镇 分组，各生成一个「已下达」新批次并复制人员，供乡镇重新填报 */
    @PostMapping("/rebuild")
    @Transactional(rollbackFor = Exception.class)
    public R<?> rebuild(@RequestBody RebuildReq req) {
        if (req == null || req.getDetailIds() == null || req.getDetailIds().isEmpty())
            throw new BizException("请选择需要重构的数据");

        // 取明细 + 其所属批次，按 项目_乡镇 分组
        Map<Long, YktBatch> batchCache = new HashMap<>();
        Map<String, List<YktGrantDetail>> groups = new LinkedHashMap<>();
        Map<String, YktBatch> groupSrcBatch = new HashMap<>();
        for (Long id : req.getDetailIds()) {
            YktGrantDetail d = grantMapper.selectById(id);
            if (d == null) continue;
            if (!CORRECTABLE.contains(d.getPayStatus())) continue;   // 只处理退款/退回
            YktBatch src = batchCache.computeIfAbsent(d.getBatchId(), batchMapper::selectById);
            if (src == null) continue;
            assertScope(src);   // 县域越权兜底：源批次乡镇须在本人范围内（防直连传别县 detailId）
            String key = src.getProjectId() + "_" + src.getTownId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            groupSrcBatch.putIfAbsent(key, src);
        }
        if (groups.isEmpty()) throw new BizException("选中数据无可重构记录（须为已退款/已退回）");

        int batchCount = 0, personCount = 0, seq = 0;
        for (Map.Entry<String, List<YktGrantDetail>> e : groups.entrySet()) {
            YktBatch src = groupSrcBatch.get(e.getKey());
            // 发放轮次：取组内明细当前次数最大值 +1（首次失败重构=第2次发放）
            int round = e.getValue().stream()
                    .mapToInt(d -> (d.getRetryTimes() == null ? 0 : d.getRetryTimes())).max().orElse(0) + 2;
            String srcName = src.getBatchName() == null ? "" : src.getBatchName();
            // 去掉旧的"更正发放(第N次)--"前缀，避免层层叠加
            srcName = srcName.replaceFirst("^更正发放(（第\\d+次）)?--", "");
            YktBatch nb = new YktBatch();
            nb.setProjectId(src.getProjectId());
            nb.setTownId(src.getTownId());
            nb.setBatchCode(genBatchCode(seq++));
            nb.setBatchName("更正发放（第" + round + "次）--" + srcName);
            nb.setFundTitle(src.getFundTitle());
            nb.setStatus("ISSUED");          // 已下达 → 乡镇可在「待编制花名册」看到并填报
            nb.setAuditStage("DRAFT");
            nb.setLastResult("待编制");
            batchMapper.insert(nb);
            writeStartLog(nb.getId());
            batchCount++;

            int sortNo = 1;
            for (YktGrantDetail s : e.getValue()) {
                YktGrantDetail nd = new YktGrantDetail();
                nd.setBatchId(nb.getId());
                nd.setBatchCode(nb.getBatchCode());
                nd.setSortNo(sortNo++);
                nd.setPayStatus("已申请");   // 重置支付状态，乡镇重新填报送审
                nd.setFailReason(null);
                nd.setRetryTimes((s.getRetryTimes() == null ? 0 : s.getRetryTimes()) + 1);  // 二次/三次发放计数
                nd.setHolderName(s.getHolderName());
                nd.setHolderIdCard(s.getHolderIdCard());
                nd.setPayeeName(s.getPayeeName());
                nd.setPayeeIdCard(s.getPayeeIdCard());
                nd.setBankAccount(s.getBankAccount());
                nd.setBankName(s.getBankName());
                nd.setVillageName(s.getVillageName());
                nd.setGroupName(s.getGroupName());
                nd.setBeneficiaryName(s.getBeneficiaryName());
                nd.setBeneficiaryIdCard(s.getBeneficiaryIdCard());
                nd.setPhone(s.getPhone());
                nd.setResidence(s.getResidence());
                nd.setAge(s.getAge());
                nd.setStandard(s.getStandard());
                nd.setAmount(s.getAmount());
                nd.setFillDate(LocalDate.now());
                nd.setRelationship(s.getRelationship());
                grantMapper.insert(nd);
                // 源明细标记「已重构」，从待更正列表移除，避免重复重构
                YktGrantDetail su = new YktGrantDetail();
                su.setId(s.getId());
                su.setPayStatus("已重构");
                su.setRemark((s.getRemark() == null ? "" : s.getRemark() + "；") + "已重构至" + nb.getBatchCode());
                grantMapper.updateById(su);
                personCount++;
            }
            refreshBatchTotals(nb.getId());
        }
        return R.ok(Map.of("batchCount", batchCount, "personCount", personCount));
    }

    private String genBatchCode(int seq) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        return "6201020002" + ts + String.format("%02d", seq % 100);
    }

    /** 县域越权兜底：批次乡镇不在本人可见范围则拒（管理员/全部 allowedTowns=null 放行）。 */
    private void assertScope(YktBatch b) {
        Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        if (b == null || b.getTownId() == null || !towns.contains(b.getTownId()))
            throw new BizException("无权操作该批次（非本县数据）");
    }

    private void refreshBatchTotals(Long batchId) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        List<YktGrantDetail> list = grantMapper.selectList(
                new LambdaQueryWrapper<YktGrantDetail>().eq(YktGrantDetail::getBatchId, batchId));
        for (YktGrantDetail d : list) if (d.getAmount() != null) total = total.add(d.getAmount());
        YktBatch u = new YktBatch();
        u.setId(batchId);
        u.setPlanCount(list.size());
        u.setPlanAmount(total);
        batchMapper.updateById(u);
    }

    private void writeStartLog(Long batchId) {
        YktAuditLog log = new YktAuditLog();
        log.setBatchId(batchId);
        log.setSeqNo(1);
        log.setDoneStation("开始");
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        log.setOperator(u != null && u.getRealName() != null ? u.getRealName() : UserContext.currentUsername());
        log.setOpType("批次重构");
        log.setOpResult("更正发放");
        log.setOpinion("");
        log.setOpTime(LocalDateTime.now());
        log.setPendingStation("乡镇录入");
        auditLogMapper.insert(log);
    }
}
