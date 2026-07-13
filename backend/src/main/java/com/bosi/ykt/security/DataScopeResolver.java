package com.bosi.ykt.security;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysRole;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserProject;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysRoleMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserProjectMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
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

    public enum Scope { ALL, COUNTY, OWN_ORG }

    public static final class Ctx {
        public final Scope scope;
        public final String countyCode;        // 6 位县码
        public final Long ownTownId;           // 本机构(乡镇) id
        public final List<Long> countyTownIds; // 本县所有乡镇 id（COUNTY 用）
        Ctx(Scope s, String cc, Long own, List<Long> towns) {
            this.scope = s; this.countyCode = cc; this.ownTownId = own; this.countyTownIds = towns;
        }
        static Ctx all() { return new Ctx(Scope.ALL, null, null, null); }
        /** 最窄拒止：推不出县的非管理员账号——乡镇条件恒空(=-1)，项目只剩省级公有。 */
        static Ctx deny() { return new Ctx(Scope.OWN_ORG, "000000", -1L, null); }
    }

    private static final String REQ_ATTR = DataScopeResolver.class.getName() + ".CTX";

    /**
     * 解析当前用户的数据范围。取不到用户/机构/县 → 一律按 ALL（不拦，避免登录态异常误锁）。
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
        if (uid == null) return Ctx.all();   // 真匿名（health 等公开接口，无 JWT）不误锁；业务接口有认证拦截兜底
        SysUser u = userMapper.selectById(uid);
        // token 有效但用户已不存在（被删/停用清库）：不能回落 ALL（否则已删账号的未过期 token 可读全州），最窄拒止
        if (u == null) return Ctx.deny();
        if ("SYS_ADMIN".equals(u.getUserType())) return Ctx.all();

        Scope s = widestScope(uid);
        if (s == Scope.ALL) return Ctx.all();

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
        if ("OWN_ORG".equals(v)) return Scope.OWN_ORG;
        return Scope.ALL; // null/未知 → 与列默认一致(ALL)
    }

    private List<Long> countyTownIds(String countyCode) {
        return orgMapper.selectList(new LambdaQueryWrapper<SysOrg>()
                        .eq(SysOrg::getOrgType, "TOWN")
                        .likeRight(SysOrg::getOrgCode, countyCode))
                .stream().map(SysOrg::getId).toList();
    }

    /** 全部县码前缀 '9'+县码（7 位），供项目"非本县且非省级"判定。 */
    private String ownedCountyPrefixesCsv() {
        return orgMapper.selectList(new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getOrgType, "COUNTY"))
                .stream().map(o -> "'9" + o.getOrgCode() + "'").collect(Collectors.joining(","));
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
     * 否则回落县码规则：本县自建（9+县码）或 省级公有（非任何县码）或 无编码（不可归属→公有）。
     */
    public void applyProject(AbstractWrapper<?, ?, ?> w, String codeCol) {
        Ctx c = current();
        if (c.scope == Scope.ALL) return;
        Long uid = UserContext.currentUserId();
        if (uid != null && hasAssignedProjects(uid)) {
            // 子查询而非拼 id 列表：授权项目再多也不会踩 ORA-01795（IN 上限 1000）
            w.apply("ID IN (SELECT PROJECT_ID FROM SYS_USER_PROJECT WHERE USER_ID = " + uid + ")");
            return;
        }
        String owned = ownedCountyPrefixesCsv();
        w.apply("(" + codeCol + " LIKE '9" + c.countyCode + "%'"
                + " OR " + codeCol + " IS NULL"
                + " OR SUBSTR(" + codeCol + ",1,7) NOT IN (" + owned + "))");
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

    /** 预聚合结果 Java 侧过滤用：允许的乡镇 id 集；ALL 返回 null（不过滤）。 */
    public Set<Long> allowedTowns() {
        Ctx c = current();
        if (c.scope == Scope.ALL) return null;
        if (c.scope == Scope.OWN_ORG) return c.ownTownId == null ? Set.of() : Set.of(c.ownTownId);
        return new HashSet<>(c.countyTownIds == null ? List.of() : c.countyTownIds);
    }

    /** "= x" 或 "IN (...)"；COUNTY 空集用 "IN (-1)" 防语法错并保证零结果。 */
    private static String townInCond(Ctx c) {
        if (c.scope == Scope.OWN_ORG) return "= " + c.ownTownId;
        List<Long> ids = c.countyTownIds;
        if (ids == null || ids.isEmpty()) return "IN (-1)";
        return "IN (" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }
}
