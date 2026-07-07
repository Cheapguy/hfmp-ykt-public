package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktBatch;
import com.bosi.ykt.mapper.YktBatchMapper;
import com.bosi.ykt.mapper.YktPaymentApplyMapper;
import com.bosi.ykt.security.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.entity.YktPaymentApply;
import com.bosi.ykt.entity.YktAuditLog;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.YktAgency;
import com.bosi.ykt.mapper.YktAuditLogMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.YktAgencyMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 补贴批次维护。
 * 状态机：NEW --下达--> ISSUED --发送一体化--> SENT --(生成支付申请/发放)--> PAID/PAID_OUT
 */
@RestController
@RequestMapping("/dept/batch")
@RequiredArgsConstructor
public class YktBatchController extends BaseCrudController<YktBatchMapper, YktBatch> {
    private final YktBatchMapper mapper;
    private final YktPaymentApplyMapper paymentApplyMapper;
    private final YktAuditLogMapper auditLogMapper;
    private final SysUserMapper userMapper;
    private final SysOrgMapper orgMapper;
    private final YktAgencyMapper agencyMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;
    @Override protected YktBatchMapper getMapper() { return mapper; }

    /**
     * 下达单位候选：按当前用户所属县（org.orgCode 前 6 位）取该县乡镇政府（补贴单位）。
     * 返回 [{id: townId(=SYS_ORG 乡镇 id), name}]。与通知下发口径一致，避免县账号加载到全州乡镇。
     */
    @GetMapping("/towns")
    public R<List<Map<String, Object>>> towns() {
        String county = currentCounty();
        LambdaQueryWrapper<YktAgency> w = new LambdaQueryWrapper<YktAgency>()
                .eq(YktAgency::getIsSubsidy, "1").eq(YktAgency::getStatus, 1);
        if (county != null) w.eq(YktAgency::getCountyCode, county);
        w.orderByAsc(YktAgency::getOrderNum).orderByAsc(YktAgency::getCode);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (YktAgency a : agencyMapper.selectList(w)) {
            if (a.getTownId() == null) continue;   // 无行政区划乡镇 id 的无法下达
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", a.getTownId());
            m.put("name", a.getName());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 当前用户所属县码：org.orgCode 前 6 位；取不到返回 null=全部县（admin） */
    private String currentCounty() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null || u.getOrgId() == null) return null;
        SysOrg org = orgMapper.selectById(u.getOrgId());
        if (org == null || org.getOrgCode() == null || org.getOrgCode().length() < 6) return null;
        return org.getOrgCode().substring(0, 6);
    }

    /** 批次创建即写「开始·新增」流水，使「查看」流程进度从头完整 */
    private void writeStartLog(Long batchId) {
        YktAuditLog log = new YktAuditLog();
        log.setBatchId(batchId);
        log.setSeqNo(1);
        log.setDoneStation("开始");
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        log.setOperator(u != null && u.getRealName() != null ? u.getRealName() : UserContext.currentUsername());
        log.setOpType("新增");
        log.setOpResult("");
        log.setOpinion("");
        log.setOpTime(LocalDateTime.now());
        log.setPendingStation("乡镇录入");
        auditLogMapper.insert(log);
    }

    /** 追加流水（自动算 seqNo），批次发送等步骤接到流程进度尾部 */
    private void appendLog(Long batchId, String doneStation, String opType, String opResult, String pendingStation) {
        Long maxSeq = auditLogMapper.selectCount(new LambdaQueryWrapper<YktAuditLog>()
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
        log.setOpinion("");
        log.setOpTime(LocalDateTime.now());
        log.setPendingStation(pendingStation);
        auditLogMapper.insert(log);
    }

    @Override
    protected QueryWrapper<YktBatch> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktBatch> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        Object name = params.get("batchName");
        Object code = params.get("batchCode");
        Object status = params.get("status");
        Object townId = params.get("townId");
        if (name != null && !"".equals(name)) w.like("BATCH_NAME", name);
        if (code != null && !"".equals(code)) w.like("BATCH_CODE", code);
        if (status != null && !"".equals(status)) w.eq("STATUS", status);
        if (townId != null && !"".equals(townId)) w.eq("TOWN_ID", townId);
        // 批次发送页：仅展示已终审(SUBMITTED+DONE)及之后(已发送/已支付/已发放)的批次
        Object sendScope = params.get("sendScope");
        if (sendScope != null && !"".equals(sendScope)) {
            w.and(x -> x.or(y -> y.eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", "DONE"))
                        .or(y -> y.in("STATUS", "SENT", "PAID", "PAID_OUT")));
        }
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离：本县/本乡镇批次
        w.orderByDesc("ID");
        return w;
    }

    @Override
    public R<?> create(@RequestBody YktBatch b) {
        if (b.getStatus() == null) b.setStatus("NEW");
        if (b.getBatchCode() == null || b.getBatchCode().isBlank()) b.setBatchCode(genBatchCode(0));
        // 未送审批次 auditStage 须为 DRAFT，否则会被「发放数据审核」页(ne DRAFT)错误纳入
        if (b.getAuditStage() == null) b.setAuditStage("DRAFT");
        mapper.insert(b);
        writeStartLog(b.getId());
        return R.ok(b);
    }

    @Data
    public static class BatchCreateReq {
        private Long projectId;
        private String batchName;
        private String fundTitle;
        private String deadline;
        private String remark;
        /** 下达单位（乡镇）列表，每个乡镇生成一条批次 */
        private List<Long> townIds;
    }

    /** 按主管部门新增：选定发放表 + 多个下达单位（乡镇），每个乡镇生成一条未下达批次。*/
    @PostMapping("/batch-create")
    @Transactional(rollbackFor = Exception.class)
    public R<?> batchCreate(@RequestBody BatchCreateReq req) {
        if (req.getProjectId() == null) throw new BizException("请选择发放表");
        if (req.getBatchName() == null || req.getBatchName().isBlank()) throw new BizException("请填写批次名称");
        if (req.getTownIds() == null || req.getTownIds().isEmpty()) throw new BizException("请选择下达单位（乡镇）");
        AtomicInteger seq = new AtomicInteger(0);
        for (Long townId : req.getTownIds()) {
            YktBatch b = new YktBatch();
            b.setProjectId(req.getProjectId());
            b.setBatchName(req.getBatchName());
            b.setFundTitle(req.getFundTitle());
            b.setDeadline(req.getDeadline());
            b.setRemark(req.getRemark());
            b.setTownId(townId);
            b.setBatchCode(genBatchCode(seq.getAndIncrement()));
            b.setStatus("NEW");
            b.setAuditStage("DRAFT");
            b.setLastResult("未下达");
            mapper.insert(b);
            writeStartLog(b.getId());
        }
        return R.ok(Map.of("count", req.getTownIds().size()));
    }

    /** 批次编号：6201020002 + yyyyMMddHHmmssSSS + 两位序列，保证同批多乡镇不撞号 */
    private String genBatchCode(int seq) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        return "6201020002" + ts + String.format("%02d", seq % 100);
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        YktBatch b = mapper.selectById(id);
        if (b != null && !"NEW".equals(b.getStatus())) throw new BizException("只能删除未下达批次");
        mapper.deleteById(id);
        return R.ok();
    }

    /** 批次下达。*/
    @PostMapping("/{id}/issue")
    public R<?> issue(@PathVariable Long id) {
        return transit(id, "NEW", "ISSUED", "仅未下达批次可下达", "已下达");
    }

    /** 取消批次下达。*/
    @PostMapping("/{id}/cancel-issue")
    public R<?> cancelIssue(@PathVariable Long id) {
        YktBatch b = mapper.selectById(id);
        if (b != null && b.getBatchName() != null && b.getBatchName().startsWith("更正发放"))
            throw new BizException("更正发放批次由系统重构生成，不可取消下达");
        return transit(id, "ISSUED", "NEW", "仅已下达批次可取消下达", "未下达");
    }

    /** 批次发送一体化：仅终审(SUBMITTED+DONE)批次可发送，数据推送至上级预算系统。*/
    @PostMapping("/{id}/send")
    public R<?> send(@PathVariable Long id) {
        YktBatch b = mapper.selectById(id);
        if (b == null) throw new BizException("批次不存在");
        if (!("SUBMITTED".equals(b.getStatus()) && "DONE".equals(b.getAuditStage())))
            throw new BizException("仅终审批次可发送一体化");
        b.setStatus("SENT");
        b.setLastResult("已发送一体化");
        mapper.updateById(b);
        appendLog(id, "批次发送", "发送", "已发送一体化", "上级系统");
        return R.ok();
    }

    /** 取消发送：退回终审态；上级系统已生成支付申请则不可取消。*/
    @PostMapping("/{id}/cancel-send")
    public R<?> cancelSend(@PathVariable Long id) {
        YktBatch b = mapper.selectById(id);
        if (b == null) throw new BizException("批次不存在");
        if (!"SENT".equals(b.getStatus())) throw new BizException("仅已发送批次可取消发送");
        Long applies = paymentApplyMapper.selectCount(
                new LambdaQueryWrapper<YktPaymentApply>().eq(YktPaymentApply::getBatchId, id));
        if (applies != null && applies > 0) throw new BizException("上级系统已生成支付申请，无法取消发送");
        b.setStatus("SUBMITTED");
        b.setAuditStage("DONE");
        b.setLastResult("终审");
        mapper.updateById(b);
        appendLog(id, "批次发送", "取消", "取消发送", "结束");
        return R.ok();
    }

    private R<?> transit(Long id, String from, String to, String err, String lastResult) {
        YktBatch b = mapper.selectById(id);
        if (b == null) throw new BizException("批次不存在");
        if (!from.equals(b.getStatus())) throw new BizException(err);
        b.setStatus(to);
        if (lastResult != null) b.setLastResult(lastResult);
        mapper.updateById(b);
        return R.ok();
    }
}
