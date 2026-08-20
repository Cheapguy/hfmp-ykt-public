package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.entity.YktBizOffice;
import com.bosi.ykt.entity.YktCentralProject;
import com.bosi.ykt.entity.YktOffice;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktProjectAuditLog;
import com.bosi.ykt.entity.YktProjectFile;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import com.bosi.ykt.mapper.YktBizOfficeMapper;
import com.bosi.ykt.mapper.YktCentralProjectMapper;
import com.bosi.ykt.mapper.YktOfficeMapper;
import com.bosi.ykt.mapper.YktProjectAuditLogMapper;
import com.bosi.ykt.mapper.YktProjectFileMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 补贴项目维护 + 审核 + 纳入及挂接。手册 §七
 *
 * <p>审核链 3 棒：
 * <pre>
 * 县财政局录入(ENTRY) --送审--> 省财政厅业务处室(DEPT) --> 省财政厅农业处(AGRI，终审) --> 完成(DONE)
 *                                                            终审时生成项目编码，并可一并核定追踪代码
 * </pre>
 * 原先是 5 棒（县审核岗 COUNTY → 市州财政综合岗 SZ → 省业务处室 → 省农业处，外加信息处单独核定
 * 追踪代码）。按用户口径简化：县级只留一个财政账号(role 3 finance)负责录入送审，中间的县审核岗、
 * 市州综合岗、信息处三棒取消——对应角色 8/11/12/13 已在 V36 停用。
 *
 * <p>归口处室不再由市州综合岗挑选，直接取录入时填的「业务处室」（{@link YktProject#getDeptName()}，
 * 数据源 {@link YktBizOffice} 21 条），送审时落到 PIVOT_OFFICE_*，报表与列表照旧可用。
 *
 * <p>退回一律回到录入岗；撤销送审仅在下一岗(省业务处室)未审时可用。
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
    private final YktBizOfficeMapper bizOfficeMapper;
    private final YktProjectFileMapper fileMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

    @Value("${ykt.upload.base-dir}")
    private String baseDir;

    // ===== 审核链的 3 棒 =====
    private static final String ST_ENTRY  = "ENTRY";   // 县财政局录入（role 3 finance）
    private static final String ST_DEPT   = "DEPT";    // 省财政厅业务处室
    private static final String ST_AGRI   = "AGRI";    // 省财政厅农业处（终审）
    private static final String ST_DONE   = "DONE";
    /** 已废弃的中间棒：县审核岗 / 市州财政综合岗。仅用于识别历史在途数据，不再流转到这两棒。 */
    private static final String ST_LEGACY_COUNTY = "COUNTY";
    private static final String ST_LEGACY_SZ     = "SZ";

    /**
     * 各棒 -> 该棒岗位的角色 ID。admin 全放行。
     * <p>历史在途项目（停在已废弃的 COUNTY/SZ 棒）一并映射到省业务处室 role 9：
     * 不映射的话 assertStageRole 找不到角色会抛「当前阶段无法审核」，这些项目既审不了也退不回，
     * 只能改库救——链路简化不该把在途数据卡死。
     */
    private static final Map<String, Long> STAGE_ROLE = Map.of(
            ST_DEPT,   9L,    // 省财政厅业务处室
            ST_AGRI,   10L,   // 省财政厅农业处
            ST_LEGACY_COUNTY, 9L,
            ST_LEGACY_SZ,     9L
    );
    /** 各棒岗位名（写审核日志 / 报错文案用）。 */
    private static final Map<String, String> STAGE_NAME = Map.of(
            ST_DEPT,   "省财政厅业务处室",
            ST_AGRI,   "省财政厅农业处",
            ST_LEGACY_COUNTY, "省财政厅业务处室",
            ST_LEGACY_SZ,     "省财政厅业务处室"
    );

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
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", original);
        out.put("url", url);
        out.put("fileSize", file.getSize());        // 附件表格「文件大小」列
        out.put("uploadName", currentRealName());   // 「上传人」列，前端直接回填不再另查
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
            // 维护页（录入岗视角）：
            //   待审核 = 还在我手上、等我送审的（草稿 / 被退回）
            //   已审核 = 已经送出去的（在审 SUBMITTED + 已终审 APPROVED）
            // 原先 pending 是「未终审」把已送审的也算进来，已审核只放已终审——那样「取消送审」
            // 按钮挂在已审核页就永远点不动（已终审必然撤销失败）。按送没送出去分，两个 tab 才各有其用。
            if ("pending".equals(tab))      w.eq("AUDIT_STATUS", "DRAFT");
            else if ("audited".equals(tab)) w.in("AUDIT_STATUS", "SUBMITTED", "APPROVED");
            // all：不加状态条件
        }
        dataScope.applyProject(w, "PROJECT_CODE");   // 县域隔离：本县自建(9+县码)+省级公有
        w.orderByDesc("ID");
        return w;
    }

    private static String str(Object o, String dft) { return o == null ? dft : String.valueOf(o); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<?> create(@RequestBody YktProject p) {
        validateShortName(p);
        validateLevel(p);
        validatePolicyLevelScope(p);
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
        saveFiles(p.getId(), p.getFiles());   // 附件随表单一起提交，项目落库拿到 id 后才能归属
        return R.ok(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<?> update(@RequestBody YktProject p) {
        if (p.getId() == null) throw new BizException("缺少项目 id");
        assertProjectVisible(p.getId());   // 县域越权兜底：不能改别县项目（送审/审核等流转已各自校验，此处补普通编辑）
        validateShortName(p);
        validateLevel(p);
        validatePolicyLevelScope(p, mapper.selectById(p.getId()));   // 级次没改就不拦
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
        saveFiles(p.getId(), p.getFiles());
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

    /** 送审（批量）：录入岗 -> 省财政厅业务处室（第一棒） */
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
            // 归口处室 = 录入时填的业务处室。原先由市州综合岗审核时挑选，该岗已退场；
            // 放在送审这一刻定格（而非 create），是因为业务处室在草稿阶段还能改。
            fillPivotOffice(p);
            writeLog(p, "录入岗", "送审", "已送审", req.getOpinion(), STAGE_NAME.get(ST_DEPT));
            p.setAuditStatus("SUBMITTED");
            p.setAuditStage(ST_DEPT);
            p.setLastResult("待" + STAGE_NAME.get(ST_DEPT) + "审核");
            mapper.updateById(p);
        }
        return R.ok();
    }

    /** 用业务处室填归口处室：名称直接取 DEPT_NAME，编码回字典查（查不到留空，不影响流转）。 */
    private void fillPivotOffice(YktProject p) {
        String dept = p.getDeptName();
        if (dept == null || dept.isBlank()) return;
        p.setPivotOfficeName(dept);
        List<YktBizOffice> hit = bizOfficeMapper.selectList(
                new LambdaQueryWrapper<YktBizOffice>().eq(YktBizOffice::getOfficeName, dept.trim()));
        p.setPivotOfficeCode(hit.isEmpty() ? null : hit.get(0).getOfficeCode());
    }

    /**
     * 审核（批量）·3 棒推进：
     *  - 省财政厅业务处室(DEPT) -> 省财政厅农业处(AGRI)
     *  - 省财政厅农业处(AGRI)：终审(APPROVED) + 自动生成项目编码，可一并核定追踪代码 -> 完成(DONE)
     * 每棒都校验操作人持有该棒角色（admin 放行）。
     * 停在已废弃 COUNTY/SZ 棒的历史项目按 DEPT 处理（一步推到农业处），避免在途数据卡死。
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
            if (ST_AGRI.equals(stage)) {
                // 省财政厅农业处：终审 + 生成项目编码（编码由终审后自动生成）
                String trace = normalizedTraceCode(req.getTraceCode());
                writeLog(p, STAGE_NAME.get(ST_AGRI), "审核",
                        trace == null ? "审核通过·终审" : "审核通过·终审·核定追踪代码[" + trace + "]",
                        req.getOpinion(), "结束");
                p.setAuditStatus("APPROVED");
                p.setAuditStage(ST_DONE);
                p.setLastResult("已终审");
                if (trace != null) p.setTraceCode(trace);
                if (p.getProjectCode() == null || p.getProjectCode().isBlank())
                    p.setProjectCode(genProjectCode(p.getCreateBy()));
            } else if (ST_DEPT.equals(stage) || ST_LEGACY_COUNTY.equals(stage) || ST_LEGACY_SZ.equals(stage)) {
                writeLog(p, STAGE_NAME.get(ST_DEPT), "审核", "审核通过", req.getOpinion(), STAGE_NAME.get(ST_AGRI));
                p.setAuditStage(ST_AGRI);
                p.setLastResult("待" + STAGE_NAME.get(ST_AGRI) + "审核");
                // 历史在途项目可能还没定格归口处室（原由已退场的市州综合岗挑选），这里补上
                if (p.getPivotOfficeName() == null || p.getPivotOfficeName().isBlank()) fillPivotOffice(p);
            } else {
                throw new BizException("项目[" + p.getProjectName() + "]当前阶段无法审核");
            }
            updateRetryingOnCodeClash(p);
        }
        return R.ok();
    }

    /**
     * 保存项目；项目编码撞唯一索引(UX_PROJECT_CODE)时重算重试。
     *
     * <p>{@link #genProjectCode} 是「查本县 MAX 再 +1」，两个终审并发会读到同一个 MAX、
     * 发出同一个编码。编码既是对外业务主键、又是县域可见性的判据（'9'+县码 前缀），
     * 撞号事后极难发现。V46 的唯一索引把它变成一次可捕获的写失败，这里接住重算——
     * Oracle 对失败语句做语句级回滚，事务本身仍可用，所以能在同一事务内直接重试。
     */
    private void updateRetryingOnCodeClash(YktProject p) {
        for (int attempt = 0; ; attempt++) {
            try {
                mapper.updateById(p);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 接父类 DataIntegrityViolationException 而不是子类 DuplicateKeyException：
                // ORA-00001 经 Spring 翻译通常落在 DuplicateKeyException，但翻译链走 SQLState/
                // 子类分支时会停在父类上。这里关心的只是「唯一约束把这次写挡了」，接父类才不会漏——
                // 漏了的后果不是撞号（索引仍挡得住），而是整单 500、前端看不到「请重试」。
                String prev = p.getProjectCode();
                if (prev == null) throw e;                      // 压根没编码，不是编码撞的
                String next = genProjectCode(p.getCreateBy());
                if (next.equals(prev)) throw e;                 // 重算没变=另有约束被违反，原样上抛别吞
                if (attempt >= 4)
                    throw new BizException("项目[" + p.getProjectName() + "]编码生成冲突，请重试");
                p.setProjectCode(next);
            }
        }
    }

    /** 追踪代码校验：空/空白视为不核定（终审时选填），非空则必须合规。 */
    private static String normalizedTraceCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String code = raw.trim();
        if (!code.matches("[0-9A-Za-z_-]{1,64}"))
            throw new BizException("追踪代码只能是字母/数字/下划线/连字符，且不超过 64 位");
        return code;
    }

    /**
     * 核定追踪代码。原由省财政厅信息处单独一棒办理，该岗退场后并入终审——
     * 农业处终审时可一并填写；本接口保留给「终审时漏填、事后补录」的场景，同样由农业处(role 10)办理。
     * 仅终审(APPROVED)项目可核定；核定不改变审核状态。
     */
    @PostMapping("/trace-code")
    @Transactional(rollbackFor = Exception.class)
    public R<?> traceCode(@RequestBody FlowReq req) {
        require(req);
        if (req.getTraceCode() == null || req.getTraceCode().isBlank())
            throw new BizException("请填写追踪代码");
        assertHasRole(STAGE_ROLE.get(ST_AGRI), STAGE_NAME.get(ST_AGRI));
        String code = normalizedTraceCode(req.getTraceCode());
        for (Long id : req.getIds()) {
            YktProject p = mapper.selectById(id);
            if (p == null) continue;
            assertProjectVisible(id);
            if (!"APPROVED".equals(p.getAuditStatus()))
                throw new BizException("项目[" + p.getProjectName() + "]未终审，无法核定追踪代码");
            p.setTraceCode(code);
            writeLog(p, STAGE_NAME.get(ST_AGRI), "核定", "核定追踪代码[" + code + "]", req.getOpinion(), "结束");
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

    /**
     * 省级处室信息。审核链简化后市州综合岗退场，本接口不再参与流转，
     * 保留供历史数据的归口处室名称回显。
     */
    @GetMapping("/offices")
    public R<List<YktOffice>> offices() {
        return R.ok(officeMapper.selectList(new LambdaQueryWrapper<YktOffice>()
                .orderByAsc(YktOffice::getSortNo)));
    }

    /** 业务处室（财政归口处室）字典：表单「业务处室」下拉数据源，21 条，按编码顺序。 */
    @GetMapping("/biz-offices")
    public R<List<YktBizOffice>> bizOffices() {
        return R.ok(bizOfficeMapper.selectList(new LambdaQueryWrapper<YktBizOffice>()
                .eq(YktBizOffice::getStatus, 1)
                .orderByAsc(YktBizOffice::getSortNo, YktBizOffice::getOfficeCode)));
    }

    /**
     * 当前账号可选的政策级次 + 越界提示语。
     *
     * <p>让后端来判而不是前端照抄一份规则：层级判定依赖 SYS_ORG 的机构树（部门要往上取行政区划），
     * 前端手里只有 userType，复制判据迟早两边走偏——真正的拦截在 {@link #validatePolicyLevelScope}，
     * 本接口只是让前端能即时弹提示。lockedMsg 非空即表示「受限账号」，选到 allowed 之外就弹它。
     */
    @GetMapping("/policy-levels")
    public R<Map<String, Object>> policyLevels() {
        String only = isAdmin() ? null : LEVEL_BY_ADMIN.get(currentAdminLevel());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", only == null ? List.of("CENTRAL", "PROVINCE", "CITY", "COUNTY") : List.of(only));
        out.put("lockedMsg", only == null ? null : "只能选择" + POLICY_LABEL.getOrDefault(only, only));
        return R.ok(out);
    }

    /** 审核历史（流程进度） */
    @GetMapping("/{id}/history")
    public R<List<YktProjectAuditLog>> history(@PathVariable Long id) {
        assertProjectVisible(id);   // 县域越权兜底：与 /{id}/files 同口径，此前漏了
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<YktProjectAuditLog>()
                .eq(YktProjectAuditLog::getProjectId, id).orderByAsc(YktProjectAuditLog::getSeqNo)));
    }

    /**
     * 撤销送审（单条）。手册 §七(三)2：下一岗还没审时才可撤销，已进入后续棒则「撤销失败」。
     * 链路简化后第一棒是省财政厅业务处室，故仅当项目仍停在 DEPT 待审时允许录入岗撤回；
     * 停在历史废弃棒(COUNTY/SZ)的在途项目同样放行——它们实际也还没被任何人审过。
     */
    @PostMapping("/{id}/revoke")
    public R<?> revoke(@PathVariable Long id) {
        YktProject p = mapper.selectById(id);
        if (p == null) throw new BizException("项目不存在");
        assertProjectVisible(id);   // 县域越权兜底
        String stage = p.getAuditStage();
        boolean firstLeg = ST_DEPT.equals(stage) || ST_LEGACY_COUNTY.equals(stage) || ST_LEGACY_SZ.equals(stage);
        if (!"SUBMITTED".equals(p.getAuditStatus()) || !firstLeg)
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

    /** 行政层级 -> 该层级账号唯一允许的政策级次。生产口径：账号在哪一级，政策级次就只能选哪一级。 */
    private static final Map<String, String> LEVEL_BY_ADMIN = Map.of(
            "PROV",   "PROVINCE",
            "STATE",  "CITY",      // 州/地级市
            "COUNTY", "COUNTY"
    );
    /** 政策级次显示名，拼提示语用（与前端 POLICY 常量一致）。 */
    private static final Map<String, String> POLICY_LABEL = Map.of(
            "CENTRAL", "中央级", "PROVINCE", "省级", "CITY", "市级", "COUNTY", "县（区）级"
    );

    /**
     * 政策级次必须与账号所在行政层级一致（前端下拉里选别的会弹「只能选择××」，这里是绕过前端的兜底）。
     *
     * <p>不是「不能选高于自己的级别」而是「只能选本级」：生产上市级账号选县级同样被拒，
     * 提示语固定为「只能选择市级」。层级判据见 {@link #currentAdminLevel()}。
     * 乡镇(TOWN)、推不出层级的账号、admin 不受此限——它们不在这个业务面上。
     */
    private void validatePolicyLevelScope(YktProject p) {
        validatePolicyLevelScope(p, null);
    }

    /**
     * @param old 修改场景传库中原值；级次没变就不拦——否则历史项目（本轮之前四个级次随便选）
     *            一改别的字段就被判越界，报「只能选择县（区）级」，用户还看不出是哪个字段的锅。
     *            新增场景传 null，任何越界都拦。
     */
    private void validatePolicyLevelScope(YktProject p, YktProject old) {
        if (p.getPolicyLevel() == null) return;
        if (isAdmin()) return;
        if (old != null && p.getPolicyLevel().equals(old.getPolicyLevel())) return;
        String only = LEVEL_BY_ADMIN.get(currentAdminLevel());
        if (only == null || only.equals(p.getPolicyLevel())) return;
        throw new BizException("只能选择" + POLICY_LABEL.getOrDefault(only, only));
    }

    /**
     * 登录账号所属的行政层级（STATE 州市 / COUNTY 县区 / TOWN 乡镇 / PROV 省）；推不出返回 null。
     *
     * <p>不能直接取所属机构的 ORG_TYPE：财政局这类单位在 SYS_ORG 里是 DEPT，行政层级看不出来，
     * 得往上取它挂靠的行政区划——「某某州财政局(DEPT) → 某某州(STATE)」是市级，
     * 「甲县财政局(DEPT) → 甲县(COUNTY)」是县级（甲县是县级市，名字带"市"但不是地级）。
     */
    private String currentAdminLevel() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null || u.getOrgId() == null) return null;
        SysOrg org = orgMapper.selectById(u.getOrgId());
        if (org == null) return null;
        if (!"DEPT".equals(org.getOrgType())) return org.getOrgType();
        SysOrg parent = org.getParentId() == null ? null : orgMapper.selectById(org.getParentId());
        return parent == null ? null : parent.getOrgType();
    }

    // ===================== 政策文件附件 =====================

    /**
     * 保存项目的政策文件附件：整体重写该项目的附件行（先删后插）。
     *
     * <p>用「整体重写」而不是增量比对，是因为前端的删除/重新上传都只改本地数组，
     * 提交上来的就是最终态；增量比对要多传一份「已删除 id 列表」，反而更容易漏。
     * 附件数量是个位数，重写的代价可以忽略。
     *
     * <p>files 为 null = 本次请求没带附件字段（例如只改某几列的局部更新），保持原样不动；
     * 空数组才表示「用户把附件都删了」。这两者必须区分，否则任何不带 files 的更新都会清空附件。
     */
    private void saveFiles(Long projectId, List<YktProjectFile> files) {
        if (projectId == null || files == null) return;
        // 先按 fileUrl 记下原有行的上传人：整体重写会先删后插，重新上传要保留首传者，
        // 但这个「保留」必须从库里取，不能信请求体——否则前端随便传个 uploadBy/uploadName
        // 就能把附件的来源栏伪造成任意用户，而详情页正是拿这一列当经手凭据看的。
        Map<String, YktProjectFile> old = new HashMap<>();
        for (YktProjectFile o : loadFiles(projectId)) {
            if (o.getFileUrl() != null) old.put(o.getFileUrl(), o);
        }
        fileMapper.delete(new LambdaQueryWrapper<YktProjectFile>()
                .eq(YktProjectFile::getProjectId, projectId));
        int i = 0;
        LocalDateTime now = LocalDateTime.now();
        for (YktProjectFile f : files) {
            if (f == null || f.getFileUrl() == null || f.getFileUrl().isBlank()) continue;
            // 只接受本系统 upload 接口产出的下载地址：否则请求体可塞任意外链，
            // 附件表格里的「下载」就成了钓鱼跳板
            if (!f.getFileUrl().startsWith("/hfmp-ykt/api/files/preview/"))
                throw new BizException("附件地址不合法，请重新上传");
            YktProjectFile row = new YktProjectFile();
            row.setProjectId(projectId);
            row.setFileName(f.getFileName() == null || f.getFileName().isBlank() ? "政策文件附件" : f.getFileName());
            row.setFileSize(f.getFileSize() == null || f.getFileSize() < 0 ? 0L : f.getFileSize());
            row.setFileUrl(f.getFileUrl());
            // 上传人一律以服务端为准：同 URL 的老行保留首传者，其余（新传的）记当前用户。
            // 请求体里的 uploadBy/uploadName/uploadTime 一概不采信。
            YktProjectFile prev = old.get(f.getFileUrl());
            row.setUploadBy(prev != null ? prev.getUploadBy() : UserContext.currentUserId());
            row.setUploadName(prev != null ? prev.getUploadName() : currentRealName());
            row.setUploadTime(prev != null && prev.getUploadTime() != null ? prev.getUploadTime() : now);
            row.setSortNo(++i);
            fileMapper.insert(row);
        }
    }

    /** 读取项目附件（按序号）。 */
    private List<YktProjectFile> loadFiles(Long projectId) {
        if (projectId == null) return List.of();
        return fileMapper.selectList(new LambdaQueryWrapper<YktProjectFile>()
                .eq(YktProjectFile::getProjectId, projectId)
                .orderByAsc(YktProjectFile::getSortNo, YktProjectFile::getId));
    }

    /** 项目详情：附带政策文件附件列表（表单打开时回显附件表格）。 */
    @GetMapping("/{id}/files")
    public R<List<YktProjectFile>> files(@PathVariable Long id) {
        assertProjectVisible(id);
        return R.ok(loadFiles(id));
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
