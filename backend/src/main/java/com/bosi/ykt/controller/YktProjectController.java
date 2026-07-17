package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.entity.YktCentralProject;
import com.bosi.ykt.entity.YktOffice;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktProjectAuditLog;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import com.bosi.ykt.mapper.YktCentralProjectMapper;
import com.bosi.ykt.mapper.YktOfficeMapper;
import com.bosi.ykt.mapper.YktProjectAuditLogMapper;
import com.bosi.ykt.mapper.YktProjectMapper;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 补贴项目维护 + 审核 + 纳入及挂接。手册 §七
 *
 * <p>审核链按手册 §七(一)3「县（区）自建项目流程」的 5 棒实现：
 * <pre>
 * 县区财政局录入(ENTRY) --送审--> 县区财政局审核岗(COUNTY) --> 市州财政综合岗(SZ，选定归口处室)
 *   --> 省财政厅业务处室(DEPT) --> 省财政厅农业处(AGRI，终审+自动生成项目编码) --> 完成(DONE)
 *      ⇢ 省财政厅信息处核定追踪代码（虚线旁支，不阻断流程）
 * </pre>
 * 退回一律回到录入岗；撤销送审仅在下一岗(县审核岗)未审时可用（手册 §七(三)2）。
 * 每棒都做岗位校验（{@link #assertStageRole}），审核页「待审核」只列本岗那一棒（{@link #applyStageScope}）。
 * 维护页(forAudit=false)与审核页(forAudit=true)共用本控制器，按 tab 过滤。
 */
@RestController
@RequestMapping("/dept/project")
@RequiredArgsConstructor
public class YktProjectController extends BaseCrudController<YktProjectMapper, YktProject> {
    private final YktProjectMapper mapper;
    private final YktProjectAuditLogMapper logMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysOrgMapper orgMapper;
    private final YktCentralProjectMapper centralMapper;
    private final YktOfficeMapper officeMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    @Value("${ykt.upload.base-dir}")
    private String baseDir;

    // ===== 审核链的 5 棒（手册 §七(一)3）=====
    private static final String ST_ENTRY  = "ENTRY";   // 县区财政局录入
    private static final String ST_COUNTY = "COUNTY";  // 县区财政局审核岗
    private static final String ST_SZ     = "SZ";      // 市州财政综合岗（选定归口处室）
    private static final String ST_DEPT   = "DEPT";    // 省财政厅业务处室（=归口处室）
    private static final String ST_AGRI   = "AGRI";    // 省财政厅农业处（终审）
    private static final String ST_DONE   = "DONE";

    /** 各棒 -> 该棒岗位的角色 ID（migrate_28/29）。admin 全放行。 */
    private static final Map<String, Long> STAGE_ROLE = Map.of(
            ST_COUNTY, 13L,   // 县区财政-项目审核岗（migrate_29 归位财政局，非民政局 role 5）
            ST_SZ,     8L,    // 市州财政综合岗
            ST_DEPT,   9L,    // 省财政厅业务处室
            ST_AGRI,   10L    // 省财政厅农业处
    );
    /** 各棒岗位名（写审核日志 / 报错文案用）。 */
    private static final Map<String, String> STAGE_NAME = Map.of(
            ST_COUNTY, "县区财政局审核岗",
            ST_SZ,     "市州财政综合岗",
            ST_DEPT,   "省财政厅业务处室",
            ST_AGRI,   "省财政厅农业处"
    );
    /** 省财政厅信息处：终审后核定追踪代码。 */
    private static final long ROLE_PROV_INFO = 11L;

    @Override protected YktProjectMapper getMapper() { return mapper; }

    /**
     * 项目越权兜底：以与列表相同的 applyProject 规则再查一遍该 id，命中=可见。
     * 复用同一段 SQL 逻辑，不重复实现县码/授权判定；admin(ALL) applyProject 为空恒命中。
     * 用于 detail 读取与所有工作流写方法（送审/审核/退回/纳入/挂接等）。
     */
    private void assertProjectVisible(Long id) {
        QueryWrapper<YktProject> w = new QueryWrapper<>();
        w.eq("ID", id);
        dataScope.applyProject(w, "PROJECT_CODE");
        Long n = mapper.selectCount(w);
        if (n == null || n == 0) throw new BizException("无权操作该项目（非本县数据）");
    }

    /** detail 读取越权兜底。 */
    @Override
    protected void assertReadable(YktProject e) { if (e.getId() != null) assertProjectVisible(e.getId()); }

    /**
     * 政策文件附件上传：文件落 base-dir（uuid 重命名防覆盖/穿越），返回 /files/preview 下载地址。
     * 前端把 url 存入 POLICY_FILE；下载走免登录的 /files/preview（同公告附件口径）。
     * 走 /dept/project 前缀 = 继承菜单 301 写保护，仅项目岗可传。
     */
    @PostMapping("/upload")
    public R<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw new BizException("请选择要上传的文件");
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) original = "policy";
        String ext = com.bosi.ykt.common.UploadExt.checkedExt(original);   // 扩展名白名单
        int dot = original.lastIndexOf('.');
        String stored = UUID.randomUUID().toString().replace("-", "") + ext;

        Path dir = Paths.get(baseDir);
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(stored).normalize().toFile());

        // POLICY_FILE 是 VARCHAR2(500 BYTE)：中文 URL 编码后 9B/字，展示名截 40 字符(留扩展名)防撑爆
        String display = original;
        if (dot >= 0 && original.length() - ext.length() > 40) {
            display = original.substring(0, 40) + ext;
        } else if (dot < 0 && original.length() > 40) {
            display = original.substring(0, 40);
        }
        String url = "/hfmp-ykt/api/files/preview/" + stored
                + "?fn=" + URLEncoder.encode(display, StandardCharsets.UTF_8);

        Map<String, String> out = new LinkedHashMap<>();
        out.put("fileName", original);
        out.put("url", url);
        return R.ok(out);
    }

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
            if ("pending".equals(tab)) {
                w.eq("AUDIT_STATUS", "SUBMITTED");
                applyStageScope(w);   // 待审核只列「本岗那一棒」，避免各岗看到别人的活
            }
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
        // 工作流字段一律服务端定，忽略客户端传值：否则请求体带 auditStatus=APPROVED/projectCode
        // 即可绕过 5 棒审核链自造已终审项目（编码由省农业处终审生成、追踪代码由省信息处核定）
        p.setAuditStatus("DRAFT");
        p.setAuditStage(ST_ENTRY);
        p.setLastResult("草稿");
        p.setIncluded(0);
        p.setProjectCode(null);
        p.setTraceCode(null);
        p.setPivotOfficeCode(null);
        p.setPivotOfficeName(null);
        p.setCatalogCode(null);
        p.setCatalogName(null);
        mapper.insert(p);
        return R.ok(p);
    }

    @Override
    public R<?> update(@RequestBody YktProject p) {
        if (p.getId() == null) throw new BizException("缺少项目 id");
        assertProjectVisible(p.getId());   // 县域越权兜底：不能改别县项目（送审/审核等流转已各自校验，此处补普通编辑）
        validateShortName(p);
        validateLevel(p);
        // 工作流字段只能走 submit/approve/reject/trace-code/include/link 各自接口流转，
        // 普通编辑置 null 让 updateById 跳过（MP 忽略 null 列），防请求体直改状态/编码越权
        p.setAuditStatus(null);
        p.setAuditStage(null);
        p.setLastResult(null);
        p.setProjectCode(null);
        p.setTraceCode(null);
        p.setPivotOfficeCode(null);
        p.setPivotOfficeName(null);
        p.setIncluded(null);
        p.setCatalogCode(null);
        p.setCatalogName(null);
        mapper.updateById(p);
        return R.ok();
    }

    /** 删除兜底：县域校验之外，非 admin 仅可删草稿——在途/已终审项目须走撤销或退回，不能一删了之。 */
    @Override
    protected void assertWritable(YktProject e) {
        assertReadable(e);
        if (isAdmin()) return;
        if (e.getAuditStatus() != null && !"DRAFT".equals(e.getAuditStatus()))
            throw new BizException("仅草稿状态项目可删除，审核中请先撤销/退回");
    }

    private boolean isAdmin() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        return u != null && "SYS_ADMIN".equals(u.getUserType());
    }

    @Data
    public static class FlowReq {
        private List<Long> ids;
        private String opinion;
        /** 市州综合岗审核时选定的归口处室 */
        private String officeCode;
        private String officeName;
        /** 省财政厅信息处核定的追踪代码 */
        private String traceCode;
    }

    /** 送审（批量）：录入岗 -> 县区财政局审核岗（手册 §七(一)3 第一棒） */
    @PostMapping("/submit")
    @Transactional(rollbackFor = Exception.class)
    public R<?> submit(@RequestBody FlowReq req) {
        require(req);
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
            if (!"DRAFT".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非草稿状态，无法送审");
            writeLog(p, "录入岗", "送审", "已送审", req.getOpinion(), STAGE_NAME.get(ST_COUNTY));
            p.setAuditStatus("SUBMITTED");
            p.setAuditStage(ST_COUNTY);
            p.setLastResult("待" + STAGE_NAME.get(ST_COUNTY) + "审核");
            mapper.updateById(p);
        }
        return R.ok();
    }

    /**
     * 审核（批量）·5 棒推进（手册 §七(一)3）：
     *  - 县区财政局审核岗(COUNTY) -> 市州财政综合岗(SZ)
     *  - 市州财政综合岗(SZ)：须选定归口处室 -> 省财政厅业务处室(DEPT)
     *  - 省财政厅业务处室(DEPT) -> 省财政厅农业处(AGRI)
     *  - 省财政厅农业处(AGRI)：终审(APPROVED) + 自动生成项目编码 -> 完成(DONE)
     * 每棒都校验操作人持有该棒角色（admin 放行）；追踪代码由信息处另行核定，不在此链。
     */
    @PostMapping("/approve")
    @Transactional(rollbackFor = Exception.class)
    public R<?> approve(@RequestBody FlowReq req) {
        require(req);
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
            if (!"SUBMITTED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非审核中状态，无法审核");
            String stage = p.getAuditStage();
            assertStageRole(stage, p);  // 岗位校验：只有本棒岗位（或 admin）能审
            switch (stage) {
                case ST_COUNTY -> {
                    writeLog(p, STAGE_NAME.get(ST_COUNTY), "审核", "审核通过", req.getOpinion(), STAGE_NAME.get(ST_SZ));
                    p.setAuditStage(ST_SZ);
                    p.setLastResult("待" + STAGE_NAME.get(ST_SZ) + "审核");
                }
                case ST_SZ -> {
                    // 市州综合岗：必须选定归口处室，转交省财政厅业务处室继续审核（手册 §七(三)2 注）
                    if (req.getOfficeCode() == null || req.getOfficeCode().isBlank()
                            || req.getOfficeName() == null || req.getOfficeName().isBlank())
                        throw new BizException("市州综合岗审核须选定归口处室");
                    p.setPivotOfficeCode(req.getOfficeCode());
                    p.setPivotOfficeName(req.getOfficeName());
                    p.setAuditStage(ST_DEPT);
                    p.setLastResult("待" + STAGE_NAME.get(ST_DEPT) + "审核");
                    writeLog(p, STAGE_NAME.get(ST_SZ), "审核", "审核通过·选定归口处室[" + req.getOfficeName() + "]",
                            req.getOpinion(), STAGE_NAME.get(ST_DEPT));
                }
                case ST_DEPT -> {
                    writeLog(p, STAGE_NAME.get(ST_DEPT), "审核", "审核通过", req.getOpinion(), STAGE_NAME.get(ST_AGRI));
                    p.setAuditStage(ST_AGRI);
                    p.setLastResult("待" + STAGE_NAME.get(ST_AGRI) + "审核");
                }
                case ST_AGRI -> {
                    // 省财政厅农业处：终审 + 生成项目编码（手册：编码由终审后自动生成）
                    writeLog(p, STAGE_NAME.get(ST_AGRI), "审核", "审核通过·终审", req.getOpinion(), "结束");
                    p.setAuditStatus("APPROVED");
                    p.setAuditStage(ST_DONE);
                    p.setLastResult("已终审");
                    if (p.getProjectCode() == null || p.getProjectCode().isBlank())
                        p.setProjectCode(genProjectCode(p.getCreateBy()));
                }
                default -> throw new BizException("项目[" + p.getProjectName() + "]当前阶段无法审核");
            }
            mapper.updateById(p);
        }
        return R.ok();
    }

    /**
     * 省财政厅信息处核定追踪代码（手册 §七(一) 流程图虚线旁支）。
     * 仅终审(APPROVED)项目可核定；核定不改变审核状态，是终审后的补充动作。
     */
    @PostMapping("/trace-code")
    @Transactional(rollbackFor = Exception.class)
    public R<?> traceCode(@RequestBody FlowReq req) {
        require(req);
        if (req.getTraceCode() == null || req.getTraceCode().isBlank())
            throw new BizException("请填写追踪代码");
        assertHasRole(ROLE_PROV_INFO, "省财政厅信息处");
        String code = req.getTraceCode().trim();
        if (!code.matches("[0-9A-Za-z_-]{1,64}"))
            throw new BizException("追踪代码只能是字母/数字/下划线/连字符，且不超过 64 位");
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            assertProjectVisible(id);
            if (!"APPROVED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]未终审，无法核定追踪代码");
            p.setTraceCode(code);
            writeLog(p, "省财政厅信息处", "核定", "核定追踪代码[" + code + "]", req.getOpinion(), "结束");
            mapper.updateById(p);
        }
        return R.ok();
    }

    /** 当前棒岗位校验：操作人须持有该棒角色（admin 放行）。 */
    private void assertStageRole(String stage, YktProject p) {
        Long need = STAGE_ROLE.get(stage);
        if (need == null) throw new BizException("项目[" + p.getProjectName() + "]当前阶段无法审核");
        assertHasRole(need, STAGE_NAME.get(stage));
    }

    /**
     * 审核页「待审核」按岗位收窄到本岗那一棒。
     * admin / 未识别岗位（例如同时持多岗）→ 不收窄，仍能看到全部 SUBMITTED；
     * 恰好持某几棒的岗位 → 只看这几棒对应的 AUDIT_STAGE。
     */
    private void applyStageScope(QueryWrapper<YktProject> w) {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        // token 有效但用户已删：对齐 DataScopeResolver 的最窄拒止，不能按 admin 口径全见
        if (uid != null && u == null) { w.apply("1 = 0"); return; }
        if (u == null || "SYS_ADMIN".equals(u.getUserType())) return;   // admin 全见
        Set<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).collect(java.util.stream.Collectors.toSet());
        // 该用户各角色能审的棒
        List<String> stages = STAGE_ROLE.entrySet().stream()
                .filter(e -> roleIds.contains(e.getValue()))
                .map(Map.Entry::getKey).toList();
        if (stages.isEmpty()) return;   // 无审核岗（如纯录入岗误入）→ 不额外收窄，交给上层 tab/权限控制
        w.in("AUDIT_STAGE", stages);
    }

    /** 操作人须持有指定角色；admin 放行。 */
    private void assertHasRole(long roleId, String stationName) {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u != null && "SYS_ADMIN".equals(u.getUserType())) return;
        boolean has = uid != null && !userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, uid)
                        .eq(SysUserRole::getRoleId, roleId)).isEmpty();
        if (!has) throw new BizException("当前操作须由「" + stationName + "」岗办理，你无该岗权限");
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
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
            if (!"SUBMITTED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]非审核中状态，无法退回");
            assertStageRole(p.getAuditStage(), p);   // 只有本棒岗位能退回
            String doneStation = STAGE_NAME.getOrDefault(p.getAuditStage(), "审核岗");
            writeLog(p, doneStation, "退回", "审核退回", opinion, "录入岗");
            p.setAuditStatus("DRAFT");
            p.setAuditStage(ST_ENTRY);
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

    /**
     * 撤销送审（单条）。手册 §七(三)2：下一岗(县审核岗)还没审时才可撤销，已进入后续棒则「撤销失败」。
     * 因此仅当项目仍停在第一棒 COUNTY（县审核岗待审）时允许录入岗撤回。
     */
    @PostMapping("/{id}/revoke")
    public R<?> revoke(@PathVariable Long id) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        assertProjectVisible(id);   // 县域越权兜底
        if (!"SUBMITTED".equals(p.getAuditStatus()) || !ST_COUNTY.equals(p.getAuditStage()))
            throw new BizException("下一岗已审核，撤销失败");
        writeLog(p, "录入岗", "撤销送审", "已撤销", null, "录入岗");
        p.setAuditStatus("DRAFT");
        p.setAuditStage(ST_ENTRY);
        p.setLastResult("草稿");
        mapper.updateById(p);
        return R.ok();
    }

    /** 纳入项目库（仅终审项目）。手册 §七(四) */
    @PostMapping("/{id}/include")
    public R<?> include(@PathVariable Long id) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        assertProjectVisible(id);   // 县域越权兜底
        if (!"APPROVED".equals(p.getAuditStatus())) throw new BizException("仅终审项目可纳入");
        p.setIncluded(1);
        mapper.updateById(p);
        return R.ok();
    }

    /** 挂接 / 取消挂接 中央目录清单。手册 §七(四) */
    @PostMapping("/{id}/link")
    public R<?> link(@PathVariable Long id, @RequestParam(required = false) String catalogCode) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        assertProjectVisible(id);   // 县域越权兜底
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
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
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
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
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
            assertProjectVisible(id);   // 县域越权兜底：不能操作别县项目
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

    /**
     * 终审后生成项目编码：县级项目 = '9'+创建者县码(6位)+5位序列（12 位），
     * 与县域可见性前缀规则(9+县码)一致——旧版 "962"+时间戳 生成的编码永远匹配不上任何县，
     * 会把县自建项目错判成省级公有全州可见。推不出县（州级/系统建）→ '969' 打头公有编码
     * （'969' 不会撞任何 '9'+县码：县 orgCode 均为 9900xx）。
     */
    private String genProjectCode(Long createBy) {
        String county = creatorCounty(createBy);
        if (county == null) return "969" + String.format("%09d", System.currentTimeMillis() % 1_000_000_000L);
        String prefix = "9" + county;
        long max = 0;
        for (YktProject x : mapper.selectList(new QueryWrapper<YktProject>()
                .select("PROJECT_CODE").likeRight("PROJECT_CODE", prefix))) {
            String c = x.getProjectCode();
            if (c != null && c.length() == 12) {
                try { max = Math.max(max, Long.parseLong(c.substring(7))); } catch (NumberFormatException ignore) { }
            }
        }
        return prefix + String.format("%05d", max + 1);   // MAX+1：删除/同毫秒都不撞号
    }

    /** 项目创建者所属县码（org.orgCode 前 6 位）；推不出返回 null。 */
    private String creatorCounty(Long uid) {
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null || u.getOrgId() == null) return null;
        SysOrg org = orgMapper.selectById(u.getOrgId());
        return (org != null && org.getOrgCode() != null && org.getOrgCode().length() >= 6)
                ? org.getOrgCode().substring(0, 6) : null;
    }

    private void writeLog(YktProject p, String doneStation, String opType, String opResult,
                          String opinion, String pendingStation) {
        List<Object> mx = logMapper.selectObjs(new QueryWrapper<YktProjectAuditLog>()
                .select("NVL(MAX(SEQ_NO),0)").eq("PROJECT_ID", p.getId()));
        int maxSeq = mx.isEmpty() || mx.get(0) == null ? 0 : ((Number) mx.get(0)).intValue();
        YktProjectAuditLog log = new YktProjectAuditLog();
        log.setProjectId(p.getId());
        log.setSeqNo(maxSeq + 1);
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
