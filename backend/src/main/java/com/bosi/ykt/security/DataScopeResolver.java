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
 * 每个 buildQuery 只调一次，内部 2~3 条小查询，不缓存（无状态、无 ThreadLocal 泄漏）。
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
    }

    /** 解析当前用户的数据范围。取不到用户/机构/县 → 一律按 ALL（不拦，避免登录态异常误锁）。 */
    public Ctx current() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null || u.getOrgId() == null) return Ctx.all();
        if ("SYS_ADMIN".equals(u.getUserType())) return Ctx.all();

        Scope s = widestScope(uid);
        if (s == Scope.ALL) return Ctx.all();

        SysOrg org = orgMapper.selectById(u.getOrgId());
        String cc = (org != null && org.getOrgCode() != null && org.getOrgCode().length() >= 6)
                ? org.getOrgCode().substring(0, 6) : null;
        if (cc == null) return Ctx.all();

        List<Long> townIds = (s == Scope.COUNTY) ? countyTownIds(cc) : null;
        return new Ctx(s, cc, u.getOrgId(), townIds);
    }

    /** 多角色取最宽范围：ALL > COUNTY > OWN_ORG。无角色 → ALL（无角色也无菜单，进不到业务页）。 */
    private Scope widestScope(Long uid) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) return Scope.ALL;
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
        List<Long> assigned = assignedProjectIds();
        if (!assigned.isEmpty()) {
            w.apply("ID IN (" + assigned.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
            return;
        }
        String owned = ownedCountyPrefixesCsv();
        w.apply("(" + codeCol + " LIKE '9" + c.countyCode + "%'"
                + " OR " + codeCol + " IS NULL"
                + " OR SUBSTR(" + codeCol + ",1,7) NOT IN (" + owned + "))");
    }

    /** 当前用户「分配数据」显式授权的项目 id；空=未显式分配（走县码规则）。 */
    private List<Long> assignedProjectIds() {
        Long uid = UserContext.currentUserId();
        if (uid == null) return List.of();
        return userProjectMapper.selectList(
                        new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, uid))
                .stream().map(SysUserProject::getProjectId).toList();
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
