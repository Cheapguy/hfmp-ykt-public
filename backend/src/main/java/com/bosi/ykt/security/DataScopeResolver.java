package com.bosi.ykt.security;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysRole;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserProject;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.entity.YktBatch;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysRoleMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserProjectMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import com.bosi.ykt.mapper.YktBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据范围解析 + 过滤注入（县域隔离）。
 *
 * <p>范围来自用户各角色 {@code SYS_ROLE.DATA_SCOPE}（取最宽），县/乡镇由用户机构 orgCode 前 6 位推出：
 * <ul>
 *   <li>ALL —— 不加条件（管理员 / 全州角色）</li>
 *   <li>COUNTY —— 本县所有乡镇</li>
 *   <li>OWN_ORG —— 仅本乡镇（机构 id）</li>
 * </ul>
 * 过滤以 {@code .apply(原生SQL)} 注入，兼容 QueryWrapper / LambdaQueryWrapper。
 * 拼入的都是库内数字 id 与 6 位县码，非用户输入，无 SQL 注入风险。
 * 解析结果缓存在当前请求属性（SCOPE_REQUEST，随请求结束释放，无 ThreadLocal 泄漏），
 * 一个请求内多次 apply 只解析一次。
 */
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final SysUserMapper userMapper;
    private final SysOrgMapper orgMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserProjectMapper userProjectMapper;
    private final YktBatchMapper batchMapper;

    public enum Scope { ALL, COUNTY, OWN_ORG }

    public static final class Ctx {
        public final Scope scope;
        public final String countyCode;        // 6 位县码
        public final Long ownTownId;           // 本机构(乡镇) id
        public final List<Long> countyTownIds; // 本县所有乡镇 id（COUNTY 用）
        public final boolean denied;           // 最窄拒止态（token 有效但用户已删/无机构）：应看不到任何业务数据
        /**
         * 州（市）级范围：本州所有县 + 州本级，但不含外州。
         *
         * <p>没有做成 Scope 的第四个枚举值，是因为 Scope 被 10 处 {@code == Scope.ALL} 判断引用，
         * 加枚举值要逐处决定归属，漏一处就是越权或误锁。州级在乡镇/批次/花名册这些维度上
         * 本来就等于全域（本系统只服务某某州一个州），唯独项目库导入了全省数据才需要区分，
         * 所以做成 ALL 之上的一个附加标记，只在 {@link #applyProject} 生效。
         */
        public final boolean stateScoped;
        Ctx(Scope s, String cc, Long own, List<Long> towns) {
            this(s, cc, own, towns, false, false);
        }
        Ctx(Scope s, String cc, Long own, List<Long> towns, boolean denied) {
            this(s, cc, own, towns, denied, false);
        }
        Ctx(Scope s, String cc, Long own, List<Long> towns, boolean denied, boolean stateScoped) {
            this.scope = s; this.countyCode = cc; this.ownTownId = own; this.countyTownIds = towns;
            this.denied = denied; this.stateScoped = stateScoped;
        }
        static Ctx all() { return new Ctx(Scope.ALL, null, null, null); }
        static Ctx state() { return new Ctx(Scope.ALL, null, null, null, false, true); }
        /** 最窄拒止：推不出县的非管理员账号——乡镇条件恒空(=-1)，项目走 denied 分支彻底锁死。 */
        static Ctx deny() { return new Ctx(Scope.OWN_ORG, "000000", -1L, null, true); }
    }

    private static final String REQ_ATTR = DataScopeResolver.class.getName() + ".CTX";

    /**
     * 解析当前用户的数据范围。取不到用户/机构/县 → 一律最窄拒止（deny），不再回落 ALL。
     * 结果缓存在当前 HTTP 请求属性里（一个请求多次 apply 只解析一次）；非 Web 线程直接解析。
     */
    public Ctx current() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQ_ATTR, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Ctx c) return c;
        }
        Ctx c = resolve();
        if (attrs != null) attrs.setAttribute(REQ_ATTR, c, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return c;
    }

    private Ctx resolve() {
        Long uid = UserContext.currentUserId();
        // 无 uid：JwtInterceptor 已把无 token 的请求 401 掉，能走到这里只可能是「签名有效但 uid 为空」
        // 的畸形 token——那不是匿名公开接口，给 ALL 等于凭一个空 uid 读全州。最窄拒止。
        if (uid == null) return Ctx.deny();
        SysUser u = userMapper.selectById(uid);
        // token 有效但用户已不存在（被删/停用清库）：不能回落 ALL（否则已删账号的未过期 token 可读全州），最窄拒止
        if (u == null) return Ctx.deny();
        if ("SYS_ADMIN".equals(u.getUserType())) return Ctx.all();

        Scope s = widestScope(uid);
        if (s == Scope.ALL) return stateOnly(uid) ? Ctx.state() : Ctx.all();

        SysOrg org = u.getOrgId() == null ? null : orgMapper.selectById(u.getOrgId());
        String cc = (org != null && org.getOrgCode() != null && org.getOrgCode().length() >= 6)
                ? org.getOrgCode().substring(0, 6) : null;
        if (cc == null) return Ctx.deny();   // 非管理员没配机构/县码 → 最窄（原先给 ALL=可读全州，配置缺陷变后门）

        List<Long> townIds = (s == Scope.COUNTY) ? countyTownIds(cc) : null;
        return new Ctx(s, cc, u.getOrgId(), townIds);
    }

    /** 多角色取最宽范围：ALL > COUNTY > OWN_ORG。无角色 → 最窄(OWN_ORG)——GET 类接口对 writeOnly 菜单一律放行，给 ALL 等于让无角色账号读全州。 */
    private Scope widestScope(Long uid) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) return Scope.OWN_ORG;
        Scope s = Scope.OWN_ORG;
        for (SysRole r : roleMapper.selectBatchIds(roleIds)) {
            Scope rs = parse(r.getDataScope());
            if (rs == Scope.ALL) return Scope.ALL;
            if (rs == Scope.COUNTY) s = Scope.COUNTY;
        }
        return s;
    }

    private static Scope parse(String v) {
        if ("COUNTY".equals(v)) return Scope.COUNTY;
        if ("ALL".equals(v) || "STATE".equals(v)) return Scope.ALL;   // STATE 的收窄另由 stateOnly 标记
        // null/未知一律按最窄算。原先回落 ALL，等于「DATA_SCOPE 写错一个字母」= 该角色读全州，
        // 配置疏忽直接变成越权。现库里 14 个角色取值都是这四个之一，改判据对现状零影响。
        return Scope.OWN_ORG;
    }

    /**
     * 是否「州级范围」账号：持有 DATA_SCOPE='STATE' 的角色，且没有任何真正全域(ALL)的角色。
     * 只要另有一个 ALL 角色，就按全域算——多角色一律取最宽，与 {@link #widestScope} 同口径。
     */
    private boolean stateOnly(Long uid) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) return false;
        boolean anyState = false;
        for (SysRole r : roleMapper.selectBatchIds(roleIds)) {
            String ds = r.getDataScope();
            if ("STATE".equals(ds)) anyState = true;
            else if (!"COUNTY".equals(ds) && !"OWN_ORG".equals(ds)) return false;   // 真 ALL(含 null)
        }
        return anyState;
    }

    private List<Long> countyTownIds(String countyCode) {
        return orgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                        .eq(SysOrg::getOrgType, "TOWN")
                        .likeRight(SysOrg::getOrgCode, countyCode))
                .stream().map(SysOrg::getId).toList();
    }

    /**
     * 「上级公有项目」的编码判据——不依赖行政区划前缀的那两类，两个分支共用：
     * <ol>
     *   <li>省级目录清单项目：7 位纯数字且不以 9 开头（国标科目码，如 2080502 高龄津贴），
     *       由省里下发、全省执行。<b>刻意用正面识别</b>而不是反向的「不是 9 开头就算国标码」：
     *       外州自建编码并非都以 9 开头，外州某县那条 {@code 9980020004} 就是 6 打头，
     *       反向判据会把它当成国标码放行，本州账号的项目列表里就会冒出一条外州某县残联的补助。</li>
     *   <li>{@code '969'} 打头的 12 位编码：YktProjectController.genProjectCode 在
     *       推不出创建人所属县时（州级账号 / 系统建）用它兜底，其 javadoc 明确定义为「公有编码」。
     *       漏掉这条的话，这类项目一经生成就落不进任何县前缀、也不是 7 位国标码，
     *       除 admin 外谁都看不见，连创建人自己都查不到。</li>
     * </ol>
     */
    private static String publicCodeCond(String codeCol) {
        return "(REGEXP_LIKE(" + codeCol + ", '^[0-8][0-9]{6}$')"
                + " OR REGEXP_LIKE(" + codeCol + ", '^969[0-9]{9}$'))";
    }

    /**
     * 州级可见的编码前缀：本州 8 个县市 + 州本级 + 省本级。
     * 某某州即 9990001(甲县)、9990002~9990008(乙县…辛县)、9990000(州本级)、9900000(省本级)。
     */
    private String stateScopePrefixesCsv() {
        String counties = orgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                        .eq(SysOrg::getOrgType, "COUNTY"))
                .stream().filter(o -> o.getOrgCode() != null && o.getOrgCode().length() >= 6)
                .map(o -> "'9" + o.getOrgCode().substring(0, 6) + "'")
                .distinct().collect(Collectors.joining(","));
        String publics = publicPrefixesCsv();
        return counties.isEmpty() ? publics : counties + "," + publics;
    }

    /**
     * 本州行政区划码的前 4 位（某某州 990000 → {@code 9900}），用于把「本州所有机构」框出来。
     * 机构表没配 STATE 行时回落 {@code 0000}，即一个都框不中——宁可少看，不能多看。
     */
    private String statePrefix4() {
        return orgMapper.selectList(new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getOrgType, "STATE"))
                .stream().map(SysOrg::getOrgCode)
                .filter(x -> x != null && x.length() >= 4)
                .findFirst().map(x -> x.substring(0, 4)).orElse("0000");
    }

    private String publicPrefixesCsv() {
        List<SysOrg> orgs = orgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                .in(SysOrg::getOrgType, List.of("PROV", "STATE")));
        String csv = orgs.stream()
                .filter(o -> o.getOrgCode() != null && o.getOrgCode().length() >= 6)
                .map(o -> "'9" + o.getOrgCode().substring(0, 6) + "'")
                .distinct().collect(Collectors.joining(","));
        // 兜底：机构表没配省/州行时至少认省本级，否则 IN () 是语法错误
        return csv.isEmpty() ? "'9990000'" : csv;
    }

    // ============ 过滤注入 ============

    /** 直接按 townCol 过滤（YKT_BATCH / YKT_BENEFICIARY / YKT_VILLAGE 的 TOWN_ID）。 */
    public void applyTown(AbstractWrapper<?, ?, ?> w, String townCol) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        w.apply(townCol + " " + townInCond(c));
    }

    /**
     * 放宽到「本县所有乡镇」的过滤（跨乡镇引用等县内共享场景）：OWN_ORG 也放到县，跨县仍拦。
     * 子查询按县码前缀取乡镇，OWN_ORG 无需预取 countyTownIds；deny 态县码 000000 → 恒空集。
     */
    public void applyCountyTown(AbstractWrapper<?, ?, ?> w, String townCol) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        w.apply(townCol + " IN (SELECT ID FROM SYS_ORG WHERE ORG_TYPE = 'TOWN' AND ORG_CODE LIKE '"
                + c.countyCode + "%')");
    }

    /** 经 batchId 子查询过滤（YKT_GRANT_DETAIL / YKT_ROSTER，本表无 townId）。 */
    public void applyBatchTown(AbstractWrapper<?, ?, ?> w, String batchIdCol) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        w.apply(batchIdCol + " IN (SELECT ID FROM YKT_BATCH WHERE TOWN_ID " + townInCond(c) + ")");
    }

    /**
     * 项目可见性。优先「分配数据」显式授权：该用户在 SYS_USER_PROJECT 有行 → 仅这些项目 id；
     * 否则回落县码规则：本县自建（9+县码）或 省级公有（非任何县码）。
     *
     * <p>两分支都放行「在途项目」（无编码，即终审前的草稿/审核中）：项目编码终审后才生成，
     * 在途项目既无县前缀、也未进授权表，若不放行则本县审核链根本走不动（录入岗送不了审、
     * 审核岗看不到本县待审）。在途项目按创建人所属县归属，只对同县可见，不泄漏别县草稿。
     */
    public void applyProject(AbstractWrapper<?, ?, ?> w, String codeCol) {
        Ctx c = current();
        // 已删账号（token 未过期）：不走白名单/县码/在途任何放行分支，彻底锁死。
        // 否则其残留的 SYS_USER_PROJECT 白名单行仍会让已删账号读到被分配项目。
        // 放在所有放行分支之前：deny 与 stateScoped 目前互斥（见 Ctx 的两个工厂方法），
        // 但拒止判断的位置不该依赖别处的构造细节。
        if (c.denied) { w.apply("1 = 0"); return; }
        Long uid = UserContext.currentUserId();
        // 州（市）级：本州 8 县 + 州本级 + 上级公有，外州一概不可见。
        // 项目库导进来的是全省数据，州财政局只管本州这 9 个前缀，外州某县、外州某市、外州某市那些跟它无关。
        // 无编码的在途项目同样按创建人所属县收窄，与县级分支一致——否则外州账号（若有）建的草稿
        // 会被州级账号看见甚至编辑（role 14 复制了 role 3 的菜单，含「补贴项目维护」）。
        if (c.stateScoped) {
            w.apply("(SUBSTR(" + codeCol + ",1,7) IN (" + stateScopePrefixesCsv() + ")"
                    + " OR " + publicCodeCond(codeCol)
                    + " OR (" + codeCol + " IS NULL AND CREATE_BY IN ("
                    + "SELECT su.ID FROM SYS_USER su JOIN SYS_ORG so ON so.ID = su.ORG_ID"
                    + " WHERE SUBSTR(so.ORG_CODE,1,4) = '" + statePrefix4() + "')))");
            return;
        }
        if (c.scope == Scope.ALL) return;
        String inflight = inflightCountyClause(codeCol, c.countyCode, uid);
        if (uid != null && hasAssignedProjects(uid)) {
            // 子查询而非拼 id 列表：授权项目再多也不会踩 ORA-01795（IN 上限 1000）
            w.apply("(ID IN (SELECT PROJECT_ID FROM SYS_USER_PROJECT WHERE USER_ID = " + uid + ")"
                    + inflight + ")");
            return;
        }
        // 可见 = 本县自建 OR 上级公有 OR 非自建编码格式（国标目录码等） OR 在途兜底
        //
        // 原判据是「前7位不在本州 8 个县的前缀里 = 省级公有」。那是个开口子的写法：
        // 全省项目库导入后，外州某县(9998003)、外州某县(9998004)、外州某县(9998005) 这些外州县项目的前缀
        // 同样不在本州 8 县里，于是全被判成省级公有，每个县账号都能看到近 900 条外州项目。
        // 现改为白名单——公有只认省本级(9990000)与本州本级(9990000)两个前缀，
        // 其余 '9'+6位数字 一律视为某县自建，只有前缀匹配的那个县看得见。
        String publics = publicPrefixesCsv();
        w.apply("(" + codeCol + " LIKE '9" + c.countyCode + "%'"
                + " OR SUBSTR(" + codeCol + ",1,7) IN (" + publics + ")"
                + " OR " + publicCodeCond(codeCol)
                + inflight + ")");
    }

    /**
     * 「在途项目」可见子句（前置 " OR "）：无编码项目按创建人所属县归属。
     * 本人创建的恒可见（CREATE_BY=uid）——assigned 白名单分支的用户建的项目终审后编码非空、
     * 又不在自己白名单里，只有靠这条才能看到自己建的项目，删不得；同县他人创建的在途项目对本县审核岗可见。
     * 已删账号的 deny 态已在 applyProject 上游 1=0 锁死，不会经此漏看。
     * countyCode 为库内 6 位码、uid 为服务端 long，均非用户输入，无注入面。
     */
    private String inflightCountyClause(String codeCol, String countyCode, Long uid) {
        StringBuilder sb = new StringBuilder();
        if (uid != null) sb.append(" OR CREATE_BY = ").append(uid);
        if (countyCode != null) {
            sb.append(" OR (").append(codeCol).append(" IS NULL AND CREATE_BY IN (")
              .append("SELECT su.ID FROM SYS_USER su JOIN SYS_ORG so ON so.ID = su.ORG_ID")
              .append(" WHERE SUBSTR(so.ORG_CODE,1,6) = '").append(countyCode).append("'))");
        }
        return sb.toString();
    }

    /**
     * 「属于某个县的用户」子查询：机构挂到县且 orgCode 够 6 位的账号 id 集。
     * 供 {@link #applyCreatorCounty} 反向识别「公共数据」——不在此集合里的创建人，
     * 就是推不出县的（系统导入 CREATE_BY 为 NULL、账号已删、机构未配码）。
     * ID 是主键恒非 NULL，用在 NOT IN 里不会踩「子查询含 NULL 致整表达式为 NULL」的坑。
     */
    private static final String COUNTY_USER_IDS =
            "SELECT su.ID FROM SYS_USER su JOIN SYS_ORG so ON so.ID = su.ORG_ID"
                    + " WHERE so.ORG_CODE IS NOT NULL AND LENGTH(so.ORG_CODE) >= 6";

    /** 某账号所属县码；推不出（账号不存在/无机构/机构无码）返回 null = 公共数据。 */
    private String countyOfUser(Long userId) {
        if (userId == null) return null;
        SysUser u = userMapper.selectById(userId);
        if (u == null || u.getOrgId() == null) return null;
        SysOrg o = orgMapper.selectById(u.getOrgId());
        return (o != null && o.getOrgCode() != null && o.getOrgCode().length() >= 6)
                ? o.getOrgCode().substring(0, 6) : null;
    }

    /**
     * 按「创建人所属县」过滤——用于既无 TOWN_ID 也无项目编码列的县内数据（政策库 YKT_POLICY、
     * 通知公告 YKT_NOTICE）。这两张表此前只有 TENANT_ID 隔离，八个县的主管部门账号共用 role 4，
     * 于是彼此的政策/公告可读可改可删。
     *
     * <p>可见 = 本县账号创建 <b>OR</b> 推不出县的公共数据（系统导入的省/州政策、上级下发公告）。
     * 公共数据放行是刻意的：516 条政策里 514 条是导入的上级政策，各县执行时都要看；
     * 但写入面 {@link #assertCreatorCounty} 反过来禁止非 ALL 账号动它们——
     * 读放宽、写收紧的不对称是本方法的设计要点，别为了"对称"把任一边改掉。
     */
    public void applyCreatorCounty(AbstractWrapper<?, ?, ?> w, String createByCol) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        if (c.denied) { w.apply("1 = 0"); return; }
        w.apply("(" + createByCol + " IS NULL"
                + " OR " + createByCol + " NOT IN (" + COUNTY_USER_IDS + ")"
                + " OR " + createByCol + " IN (" + COUNTY_USER_IDS
                + " AND SUBSTR(so.ORG_CODE,1,6) = '" + c.countyCode + "'))");
    }

    /**
     * 读取面兜底判定（detail 按 id 直读用，与 {@link #applyCreatorCounty} 同口径）：
     * 本县自建 OR 公共数据（推不出县）→ 可读。
     */
    public boolean creatorCountyReadable(Long createBy) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return true;
        if (c.denied) return false;
        String cc = countyOfUser(createBy);
        return cc == null || cc.equals(c.countyCode);
    }

    /**
     * 写入面兜底：只能改/删本县账号创建的数据。
     * 与 {@link #applyCreatorCounty} 的读口径刻意不同——公共数据（创建人推不出县）读得到但写不动，
     * 否则任一县账号都能删掉全州共用的上级政策。ALL（管理员/州级）放行。
     */
    public void assertCreatorCounty(Long createBy, String label) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        String cc = countyOfUser(createBy);
        if (cc == null) throw new BizException("无权操作" + label + "（上级下发数据，仅可查看）");
        if (!cc.equals(c.countyCode)) throw new BizException("无权操作" + label + "（非本县数据）");
    }

    /** 该用户是否有「分配数据」显式授权；无=走县码规则。 */
    private boolean hasAssignedProjects(Long uid) {
        Long n = userProjectMapper.selectCount(
                new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, uid));
        return n != null && n > 0;
    }

    /**
     * 机构树 / 乡镇下拉按范围裁剪（/sys/org/tree 的数据源）。
     * ALL → 不过滤（管理员分配数据弹窗仍看全量）；
     * COUNTY → 本县全部机构（县节点 + 县内乡镇 + 县属部门，orgCode likeRight 县码）；
     * OWN_ORG → 仅本乡镇 + 本县节点（留县节点供前端树挂父级）。
     */
    public void applyOrgTree(LambdaQueryWrapper<SysOrg> w) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        if (c.scope == Scope.COUNTY) {
            w.likeRight(SysOrg::getOrgCode, c.countyCode);
        } else { // OWN_ORG：本乡镇 + 本县节点
            w.and(x -> x.eq(SysOrg::getId, c.ownTownId).or().eq(SysOrg::getOrgCode, c.countyCode));
        }
    }

    /** {@link #applyOrgTree(LambdaQueryWrapper)} 的 QueryWrapper 版：给 BaseCrud 的 page/list 用。 */
    public void applyOrgTree(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysOrg> w) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        if (c.scope == Scope.COUNTY) {
            w.likeRight("ORG_CODE", c.countyCode);
        } else {
            w.and(x -> x.eq("ID", c.ownTownId).or().eq("ORG_CODE", c.countyCode));
        }
    }

    /**
     * 单条机构是否在当前范围内（detail 越权兜底）。
     * 判据必须与 {@link #applyOrgTree} 逐字对应，否则 page/list/tree 收紧了、
     * 按 id 直取的 detail 却漏一道——机构不是身份证，但它是数据范围的根。
     * 特别注意 OWN_ORG：树里是「本乡镇 + 本县节点」精确匹配，不是县码前缀，
     * 前缀写法会把同县其它乡镇一并放进来。
     */
    public boolean orgVisible(SysOrg org) {
        if (org == null) return false;
        Ctx c = current();
        if (c.scope == Scope.ALL) return true;
        String code = org.getOrgCode();
        if (c.scope == Scope.COUNTY)
            return c.countyCode != null && code != null && code.startsWith(c.countyCode);
        // OWN_ORG
        return java.util.Objects.equals(org.getId(), c.ownTownId)
                || (c.countyCode != null && c.countyCode.equals(code));
    }

    /** 预聚合结果 Java 侧过滤用：允许的乡镇 id 集；ALL 返回 null（不过滤）。 */
    public Set<Long> allowedTowns() {
        Ctx c = current();
        if (c.scope == Scope.ALL) return null;
        if (c.scope == Scope.OWN_ORG) return c.ownTownId == null ? Set.of() : Set.of(c.ownTownId);
        return new HashSet<>(c.countyTownIds == null ? List.of() : c.countyTownIds);
    }

    // ============ 写路径越权兜底（单一真源） ============
    // 读取面由 apply* 在 SQL 层收窄；写入面（create/update/delete 及各审批推进）按目标乡镇逐一断言。
    // 以下三个方法是全工程写路径校验的唯一实现，各控制器的 assertXxx 一律委托到此，
    // 避免「allowedTowns()==null 放行 + contains + throw」在十余处各抄一份、口径漂移成越权盲区。

    /** 越权兜底判定：管理员(allowedTowns()==null) 放行；否则 townId 必须落在允许乡镇集内。 */
    public boolean townInScope(Long townId) {
        Set<Long> towns = allowedTowns();
        return towns == null || (townId != null && towns.contains(townId));
    }

    /** 越权兜底断言：目标乡镇不在范围抛 BizException。label 拼进文案，如「该批次」「该补贴对象」。 */
    public void assertTown(Long townId, String label) {
        if (!townInScope(townId)) throw new BizException("无权操作" + label + "（非本县数据）");
    }

    /**
     * 「须本乡镇亲自操作」断言：判**经办者身份**而非数据范围，用于两方审批这类不能一人分饰两角的场景。
     *
     * <p>与 {@link #assertTown} 的区别：COUNTY 范围账号（县级部门）allowedTowns 覆盖全县乡镇，
     * assertTown 会同时放行「甲乡镇发起」和「乙乡镇审批」，一个县级账号就能把两方流程独自走完。
     * 本方法要求 townId == 本人机构 id，县级账号（机构挂部门而非乡镇）自然进不来——
     * 县级仍能通过数据范围**看见**这些数据，只是点不动。
     *
     * <p>ALL（管理员）放行：属运维兜底通道，不是业务角色（它本就能直接改库，拦无意义）。
     */
    public void assertOwnTown(Long townId, String label) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        if (townId == null || !townId.equals(c.ownTownId))
            throw new BizException("仅" + label + "本机构账号可执行此操作");
    }

    /**
     * 经批次 id 反查乡镇后兜底（本表无 townId、只握 batchId 的接口用，如花名册/更正/支付）。
     * 管理员免查直接放行；非管理员批次不存在按越权处理（防直连传别县 batchId 探测）。
     */
    public void assertBatch(Long batchId, String label) {
        if (allowedTowns() == null) return;   // 管理员：免一次 selectById
        YktBatch b = batchId == null ? null : batchMapper.selectById(batchId);
        assertTown(b == null ? null : b.getTownId(), label);
    }

    /** "= x" 或 "IN (...)"；COUNTY 空集用 "IN (-1)" 防语法错并保证零结果。 */
    private static String townInCond(Ctx c) {
        if (c.scope == Scope.OWN_ORG) return "= " + c.ownTownId;
        List<Long> ids = c.countyTownIds;
        if (ids == null || ids.isEmpty()) return "IN (-1)";
        return "IN (" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }
}
