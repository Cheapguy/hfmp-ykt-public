package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.YktCentralProject;
import com.bosi.ykt.entity.YktOffice;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktProjectAuditLog;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.YktCentralProjectMapper;
import com.bosi.ykt.mapper.YktOfficeMapper;
import com.bosi.ykt.mapper.YktProjectAuditLogMapper;
import com.bosi.ykt.mapper.YktProjectMapper;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 补贴项目维护 + 审核 + 纳入及挂接。
 * 录入岗 --送审--> 审核岗 --审核(终审,自动生成项目编码)--> 结束；退回回到录入岗。
 * 维护页(forAudit=false)与审核页(forAudit=true)共用本控制器，按 tab 过滤。
 */
@RestController
@RequestMapping("/dept/project")
@RequiredArgsConstructor
public class YktProjectController extends BaseCrudController<YktProjectMapper, YktProject> {
    private final YktProjectMapper mapper;
    private final YktProjectAuditLogMapper logMapper;
    private final SysUserMapper userMapper;
    private final YktCentralProjectMapper centralMapper;
    private final YktOfficeMapper officeMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    @Override protected YktProjectMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktProject> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktProject> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        Object name = params.get("projectName");
        Object code = params.get("projectCode");
        Object included = params.get("included");
        if (name != null && !"".equals(name)) w.like("PROJECT_NAME", name);
        if (code != null && !"".equals(code)) w.like("PROJECT_CODE", code);
        if (included != null && !"".equals(included)) w.eq("INCLUDED", included);

        String tab = str(params.get("tab"), "pending");
        boolean forAudit = "true".equalsIgnoreCase(str(params.get("forAudit"), "false"));
        if (forAudit) {
            // 审核页：审核人看不到草稿
            if ("pending".equals(tab))      w.eq("AUDIT_STATUS", "SUBMITTED");
            else if ("audited".equals(tab)) w.eq("AUDIT_STATUS", "APPROVED");
            else                            w.in("AUDIT_STATUS", "SUBMITTED", "APPROVED");
        } else {
            // 维护页：待审核=未终审(草稿/已送审)
            if ("pending".equals(tab))      w.ne("AUDIT_STATUS", "APPROVED");
            else if ("audited".equals(tab)) w.eq("AUDIT_STATUS", "APPROVED");
            // all：不加状态条件
        }
        dataScope.applyProject(w, "PROJECT_CODE");   // 县域隔离：本县自建(9+县码)+省级公有
        w.orderByDesc("ID");
        return w;
    }

    private static String str(Object o, String dft) { return o == null ? dft : String.valueOf(o); }

    @Override
    public R<?> create(@RequestBody YktProject p) {
        validateShortName(p);
        validateLevel(p);
        if (p.getAuditStatus() == null) p.setAuditStatus("DRAFT");
        if (p.getAuditStage() == null) p.setAuditStage("ENTRY");
        if (p.getLastResult() == null) p.setLastResult("草稿");
        if (p.getIncluded() == null) p.setIncluded(0);
        mapper.insert(p);
        return R.ok(p);
    }

    @Override
    public R<?> update(@RequestBody YktProject p) {
        validateShortName(p);
        validateLevel(p);
        mapper.updateById(p);
        return R.ok();
    }

    @Data
    public static class FlowReq {
        private List<Long> ids;
        private String opinion;
        /** 市州综合岗审核时选定的归口处室 */
        private String officeCode;
        private String officeName;
    }

    /** 送审（批量）：录入岗 -> 市州财政综合岗 */
    @PostMapping("/submit")
    @Transactional(rollbackFor = Exception.class)
    public R<?> submit(@RequestBody FlowReq req) {
        require(req);
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (!"DRAFT".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非草稿状态，无法送审");
            writeLog(p, "录入岗", "送审", "已送审", req.getOpinion(), "市州财政综合岗");
            p.setAuditStatus("SUBMITTED");
            p.setAuditStage("SZ");
            p.setLastResult("待市州综合岗审核");
            mapper.updateById(p);
        }
        return R.ok();
    }

    /**
     * 审核（批量）·两段制：
     *  - 市州财政综合岗(SZ)：选定归口处室 -> 转交归口处室(DEPT)，不终审；
     *  - 归口处室(DEPT)：终审(APPROVED) + 自动生成项目编码。
     */
    @PostMapping("/approve")
    @Transactional(rollbackFor = Exception.class)
    public R<?> approve(@RequestBody FlowReq req) {
        require(req);
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (!"SUBMITTED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非审核中状态，无法审核");
            String stage = p.getAuditStage();
            if ("SZ".equals(stage)) {
                // 市州综合岗：必须选定归口处室，转交归口处室继续审核
                if (req.getOfficeCode() == null || req.getOfficeCode().isBlank()
                        || req.getOfficeName() == null || req.getOfficeName().isBlank())
                    throw new BizException("市州综合岗审核须选定归口处室");
                p.setPivotOfficeCode(req.getOfficeCode());
                p.setPivotOfficeName(req.getOfficeName());
                p.setAuditStage("DEPT");
                p.setLastResult("待归口处室审核");
                writeLog(p, "市州财政综合岗", "审核", "审核通过·转交归口处室[" + req.getOfficeName() + "]",
                        req.getOpinion(), "归口处室");
                mapper.updateById(p);
            } else if ("DEPT".equals(stage)) {
                // 归口处室：终审
                writeLog(p, "归口处室", "审核", "审核通过·终审", req.getOpinion(), "结束");
                p.setAuditStatus("APPROVED");
                p.setAuditStage("DONE");
                p.setLastResult("已终审");
                if (p.getProjectCode() == null || p.getProjectCode().isBlank())
                    p.setProjectCode(genProjectCode());
                mapper.updateById(p);
            } else {
                throw new BizException("项目[" + p.getProjectName() + "]当前阶段无法审核");
            }
        }
        return R.ok();
    }

    /** 退回（批量）：回到录入岗 */
    @PostMapping("/reject")
    @Transactional(rollbackFor = Exception.class)
    public R<?> reject(@RequestBody FlowReq req) {
        require(req);
        String opinion = (req.getOpinion() == null || req.getOpinion().isBlank()) ? "退回修改" : req.getOpinion();
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (!"SUBMITTED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非审核中状态，无法退回");
            String doneStation = "DEPT".equals(p.getAuditStage()) ? "归口处室" : "市州财政综合岗";
            writeLog(p, doneStation, "退回", "审核退回", opinion, "录入岗");
            p.setAuditStatus("DRAFT");
            p.setAuditStage("ENTRY");
            p.setLastResult("审核退回");
            mapper.updateById(p);
        }
        return R.ok();
    }

    /** 省级处室信息（归口处室可选项） */
    @GetMapping("/offices")
    public R<List<YktOffice>> offices() {
        return R.ok(officeMapper.selectList(new LambdaQueryWrapper<YktOffice>()
                .orderByAsc(YktOffice::getSortNo)));
    }

    /** 审核历史（流程进度） */
    @GetMapping("/{id}/history")
    public R<List<YktProjectAuditLog>> history(@PathVariable Long id) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<YktProjectAuditLog>()
                .eq(YktProjectAuditLog::getProjectId, id).orderByAsc(YktProjectAuditLog::getSeqNo)));
    }

    /** 撤销送审（单条，纳入挂接页沿用） */
    @PostMapping("/{id}/revoke")
    public R<?> revoke(@PathVariable Long id) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        if (!"SUBMITTED".equals(p.getAuditStatus())) throw new BizException("下一岗已审核，撤销失败");
        p.setAuditStatus("DRAFT");
        p.setAuditStage("ENTRY");
        p.setLastResult("草稿");
        mapper.updateById(p);
        return R.ok();
    }

    /** 纳入项目库（仅终审项目）。*/
    @PostMapping("/{id}/include")
    public R<?> include(@PathVariable Long id) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        if (!"APPROVED".equals(p.getAuditStatus())) throw new BizException("仅终审项目可纳入");
        p.setIncluded(1);
        mapper.updateById(p);
        return R.ok();
    }

    /** 挂接 / 取消挂接 中央目录清单。*/
    @PostMapping("/{id}/link")
    public R<?> link(@PathVariable Long id, @RequestParam(required = false) String catalogCode) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        p.setCatalogCode(catalogCode);
        mapper.updateById(p);
        return R.ok();
    }

    // ===================== 纳入及挂接（批量） §七(四) =====================

    /** 待纳入清单：已终审且未纳入的项目（纳入弹窗左侧选择源） */
    @GetMapping("/includable")
    public R<List<YktProject>> includable(@RequestParam(required = false) String projectName,
                                          @RequestParam(required = false) String projectCode) {
        QueryWrapper<YktProject> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.eq("AUDIT_STATUS", "APPROVED");
        w.and(q -> q.ne("INCLUDED", 1).or().isNull("INCLUDED"));
        if (projectName != null && !projectName.isBlank()) w.like("PROJECT_NAME", projectName);
        if (projectCode != null && !projectCode.isBlank()) w.like("PROJECT_CODE", projectCode);
        dataScope.applyProject(w, "PROJECT_CODE");   // 县域隔离
        w.orderByDesc("ID");
        return R.ok(mapper.selectList(w));
    }

    @Data
    public static class IncludeReq { private List<Long> ids; }

    /** 批量纳入：终审项目 -> included=1 */
    @PostMapping("/include-batch")
    @Transactional(rollbackFor = Exception.class)
    public R<?> includeBatch(@RequestBody IncludeReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty())
            throw new BizException("请选择要纳入的项目");
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (!"APPROVED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非终审状态，无法纳入");
            p.setIncluded(1);
            mapper.updateById(p);
        }
        return R.ok();
    }

    @Data
    public static class LinkReq {
        private List<Long> ids;
        private String catalogCode;
        private String catalogName;
    }

    /** 批量挂接中央项目 */
    @PostMapping("/link-batch")
    @Transactional(rollbackFor = Exception.class)
    public R<?> linkBatch(@RequestBody LinkReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty())
            throw new BizException("请选择要挂接的项目");
        if (req.getCatalogCode() == null || req.getCatalogCode().isBlank())
            throw new BizException("请选择要挂接的中央补贴项目");
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (p.getIncluded() == null || p.getIncluded() != 1)
                throw new BizException("项目[" + p.getProjectName() + "]未纳入，无法挂接");
            p.setCatalogCode(req.getCatalogCode());
            p.setCatalogName(req.getCatalogName());
            mapper.updateById(p);
        }
        return R.ok();
    }

    /** 批量取消挂接 */
    @PostMapping("/unlink-batch")
    @Transactional(rollbackFor = Exception.class)
    public R<?> unlinkBatch(@RequestBody IncludeReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty())
            throw new BizException("请选择要取消挂接的项目");
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            if (p.getCatalogCode() == null || p.getCatalogCode().isBlank())
                throw new BizException("项目[" + p.getProjectName() + "]未挂接，无需取消");
            // updateById 默认忽略 null 字段，置空需用 UpdateWrapper 显式 set null
            mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktProject>()
                    .eq("ID", id).set("CATALOG_CODE", null).set("CATALOG_NAME", null));
        }
        return R.ok();
    }

    /** 中央补贴项目清单（挂接弹窗 + 查询页·中央项目 tab） */
    @GetMapping("/central")
    public R<List<YktCentralProject>> central(@RequestParam(required = false) String projectName,
                                              @RequestParam(required = false) String projectCode) {
        QueryWrapper<YktCentralProject> w = new QueryWrapper<>();
        if (projectName != null && !projectName.isBlank()) w.like("PROJECT_NAME", projectName);
        if (projectCode != null && !projectCode.isBlank()) w.like("PROJECT_CODE", projectCode);
        w.orderByAsc("PROJECT_CODE");
        return R.ok(centralMapper.selectList(w));
    }

    // ===================== 内部 =====================
    private void require(FlowReq req) {
        if (req == null || req.getIds() == null || req.getIds().isEmpty())
            throw new BizException("请选择要操作的项目");
    }

    private void validateShortName(YktProject p) {
        if (p.getShortName() != null && p.getShortName().length() > 7)
            throw new BizException("项目简称须控制在七个字以内");
    }

    /** 政策级次↔项目级次 区划一致性校验 */
    private void validateLevel(YktProject p) {
        String pol = p.getPolicyLevel(), prj = p.getProjectLevel();
        if (pol == null || prj == null) return;
        boolean ok = switch (pol) {
            case "COUNTY" -> "COUNTY_SELF".equals(prj);
            case "CITY"   -> "CITY_SELF".equals(prj);
            case "PROVINCE", "CENTRAL" -> "PROV_SELF".equals(prj) || "PROV_CATALOG".equals(prj);
            default -> true;
        };
        if (!ok) throw new BizException("政策级次与项目级次不匹配：请按区划选择对应的项目级次");
    }

    private String genProjectCode() {
        // 终审后生成项目编码：12 位（区划占位 + 时间序列），生产由编码规则生成，此处模拟
        return "962" + String.format("%09d", System.currentTimeMillis() % 1_000_000_000L);
    }

    private void writeLog(YktProject p, String doneStation, String opType, String opResult,
                          String opinion, String pendingStation) {
        Long maxSeq = logMapper.selectCount(new LambdaQueryWrapper<YktProjectAuditLog>()
                .eq(YktProjectAuditLog::getProjectId, p.getId()));
        YktProjectAuditLog log = new YktProjectAuditLog();
        log.setProjectId(p.getId());
        log.setSeqNo((maxSeq == null ? 0 : maxSeq.intValue()) + 1);
        log.setDoneStation(doneStation);
        log.setOperator(currentRealName());
        log.setOpType(opType);
        log.setOpResult(opResult);
        log.setOpinion(opinion == null || opinion.isBlank() ? "同意" : opinion);
        log.setOpTime(LocalDateTime.now());
        log.setPendingStation(pendingStation);
        logMapper.insert(log);
    }

    private String currentRealName() {
        Long uid = UserContext.currentUserId();
        if (uid == null) return "系统";
        SysUser u = userMapper.selectById(uid);
        return u != null && u.getRealName() != null ? u.getRealName() : UserContext.currentUsername();
    }
}
