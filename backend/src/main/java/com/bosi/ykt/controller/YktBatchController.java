package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 补贴批次维护。手册 §十、§十一(三)
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

    /** 县域越权兜底（写路径）：目标乡镇不在本人可见范围则拒（管理员/全部 allowedTowns=null 放行）。 */
    private void assertTownScope(Long townId) {
        Set<Long> towns = dataScope.allowedTowns();
        if (towns == null) return;
        if (townId == null || !towns.contains(townId))
            throw new BizException("无权操作该批次（非本县数据）");
    }

    /** 按批次 id 兜底：反查现存批次的乡镇后校验。 */
    private YktBatch assertBatchScope(Long batchId) {
        YktBatch b = batchId == null ? null : mapper.selectById(batchId);
        if (b == null) throw new BizException("批次不存在");
        assertTownScope(b.getTownId());
        return b;
    }

    /** detail 读取越权兜底：禁止跨县凭 id 越权读批次。 */
    @Override
    protected void assertReadable(YktBatch e) { assertTownScope(e.getTownId()); }

    /**
     * 当前用户所属县码；null=全部县。以数据范围为准：ALL（管理员/全州角色）→ null，
     * 否则取 DataScope 解析出的县码——此前直接拿 org.orgCode 前 6 位，管理员机构码恰好落在某县时
     * 下达单位就只剩那个县的乡镇（实测 admin 只见单县）。
     */
    private String currentCounty() {
        com.bosi.ykt.security.DataScopeResolver.Ctx c = dataScope.current();
        return c.scope == com.bosi.ykt.security.DataScopeResolver.Scope.ALL ? null : c.countyCode;
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
        java.util.List<Object> mx = auditLogMapper.selectObjs(new QueryWrapper<YktAuditLog>()
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
        assertTownScope(b.getTownId());   // 县域越权兜底：不能替别县乡镇建批次
        if (b.getStatus() == null) b.setStatus("NEW");
        if (b.getBatchCode() == null || b.getBatchCode().isBlank()) b.setBatchCode(genBatchCode(0));
        // 未送审批次 auditStage 须为 DRAFT，否则会被「发放数据审核」页(ne DRAFT)错误纳入
        if (b.getAuditStage() == null) b.setAuditStage("DRAFT");
        mapper.insert(b);
        writeStartLog(b.getId());
        return R.ok(b);
    }

    /**
     * 编辑批次基本信息：县域校验 + 仅未进流程(NEW/ISSUED)可改；
     * 状态机/资金字段一律清空（MP 忽略 null），杜绝直连 PUT 篡改 status/金额。
     */
    @Override
    public R<?> update(@RequestBody YktBatch b) {
        if (b.getId() == null) throw new BizException("缺少批次 id");
        YktBatch old = assertBatchScope(b.getId());
        if (!"NEW".equals(old.getStatus()) && !"ISSUED".equals(old.getStatus()))
            throw new BizException("批次已进入审核/支付流程，基本信息不可修改");
        if (b.getTownId() != null) assertTownScope(b.getTownId());   // 改下达单位也须在本县内
        b.setStatus(null); b.setAuditStage(null); b.setLastResult(null);
        b.setPlanCount(null); b.setPlanAmount(null);
        b.setActualCount(null); b.setActualAmount(null);
        b.setRefundAmount(null); b.setReturnAmount(null); b.setStopAmount(null);
        b.setGrantTime(null);
        mapper.updateById(b);
        return R.ok();
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

    /** 按主管部门新增：选定发放表 + 多个下达单位（乡镇），每个乡镇生成一条未下达批次。手册 §十-1 */
    @PostMapping("/batch-create")
    @Transactional(rollbackFor = Exception.class)
    public R<?> batchCreate(@RequestBody BatchCreateReq req) {
        if (req.getProjectId() == null) throw new BizException("请选择发放表");
        if (req.getBatchName() == null || req.getBatchName().isBlank()) throw new BizException("请填写批次名称");
        if (req.getTownIds() == null || req.getTownIds().isEmpty()) throw new BizException("请选择下达单位（乡镇）");
        for (Long townId : req.getTownIds()) assertTownScope(townId);   // 县域越权兜底
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

    /** 批次编号：6201020002 + yyyyMMddHHmmssSSS + 两位序列 + 三位随机，同批多乡镇/跨请求同毫秒都不撞号 */
    private String genBatchCode(int seq) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int rnd = java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);
        return "6201020002" + ts + String.format("%02d", seq % 100) + String.format("%03d", rnd);
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        YktBatch b = assertBatchScope(id);
        if (!"NEW".equals(b.getStatus())) throw new BizException("只能删除未下达批次");
        mapper.deleteById(id);
        return R.ok();
    }

    /** 批次下达。手册 §十-4 */
    @PostMapping("/{id}/issue")
    public R<?> issue(@PathVariable Long id) {
        return transit(id, "NEW", "ISSUED", "仅未下达批次可下达", "已下达");
    }

    /** 取消批次下达。手册 §十-5 */
    @PostMapping("/{id}/cancel-issue")
    public R<?> cancelIssue(@PathVariable Long id) {
        YktBatch b = mapper.selectById(id);
        if (b != null && b.getBatchName() != null && b.getBatchName().startsWith("更正发放"))
            throw new BizException("更正发放批次由系统重构生成，不可取消下达");
        return transit(id, "ISSUED", "NEW", "仅已下达批次可取消下达", "未下达");
    }

    /** 批次发送一体化：仅终审(SUBMITTED+DONE)批次可发送，数据推送至预算管理一体化系统。手册 §十一(三) */
    @PostMapping("/{id}/send")
    @Transactional(rollbackFor = Exception.class)
    public R<?> send(@PathVariable Long id) {
        assertBatchScope(id);
        int r = mapper.update(null, new UpdateWrapper<YktBatch>()
                .eq("ID", id).eq("STATUS", "SUBMITTED").eq("AUDIT_STAGE", "DONE")
                .set("STATUS", "SENT").set("LAST_RESULT", "已发送一体化"));
        if (r == 0) throw new BizException("仅终审批次可发送一体化（状态已变更，请刷新）");
        appendLog(id, "批次发送", "发送", "已发送一体化", "预算一体化");
        return R.ok();
    }

    /** 取消发送：退回终审态；预算一体化已生成支付申请则不可取消。手册 §十一(三) */
    @PostMapping("/{id}/cancel-send")
    @Transactional(rollbackFor = Exception.class)
    public R<?> cancelSend(@PathVariable Long id) {
        assertBatchScope(id);
        Long applies = paymentApplyMapper.selectCount(
                new LambdaQueryWrapper<YktPaymentApply>().eq(YktPaymentApply::getBatchId, id));
        if (applies != null && applies > 0) throw new BizException("预算一体化已生成支付申请，无法取消发送");
        int r = mapper.update(null, new UpdateWrapper<YktBatch>()
                .eq("ID", id).eq("STATUS", "SENT")
                .set("STATUS", "SUBMITTED").set("AUDIT_STAGE", "DONE").set("LAST_RESULT", "终审"));
        if (r == 0) throw new BizException("仅已发送批次可取消发送（状态已变更，请刷新）");
        appendLog(id, "批次发送", "取消", "取消发送", "结束");
        return R.ok();
    }

    /** 状态迁移（条件更新判行数：并发/越权直连拿不到 0→1 以外的结果） */
    private R<?> transit(Long id, String from, String to, String err, String lastResult) {
        assertBatchScope(id);
        UpdateWrapper<YktBatch> w = new UpdateWrapper<YktBatch>()
                .eq("ID", id).eq("STATUS", from).set("STATUS", to);
        if (lastResult != null) w.set("LAST_RESULT", lastResult);
        if (mapper.update(null, w) == 0) throw new BizException(err);
        return R.ok();
    }
}
