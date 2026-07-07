package com.bosi.ykt.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.entity.SysRoleMenu;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.mapper.SysRoleMenuMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权拦截器（接在 {@link JwtInterceptor} 之后，UserContext 已就绪）。
 *
 * 模型：API 路径前缀 -> 所需菜单 ID，复用 角色 → SYS_ROLE_MENU 的现有授权数据。
 *  - SYS_ADMIN：全量放行
 *  - writeOnly=false：任何方法都需对应菜单（≈ 管理员专属，如安全管理）
 *  - writeOnly=true：GET/HEAD 放行，写操作需对应菜单
 *  - 未命中任何前缀：放行（/auth、/dashboard、/files 等公共接口）
 */
@Component
@RequiredArgsConstructor
public class AuthorizationInterceptor implements HandlerInterceptor {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    private record Rule(long menuId, boolean writeOnly) {}

    /** 前缀 -> 规则。匹配时取最长命中前缀。菜单 ID 与 schema 种子数据一致。 */
    private static final Map<String, Rule> RULES = Map.ofEntries(
            // ===== 安全管理：全方法保护 =====
            Map.entry("/sys/user",          new Rule(101, false)),
            Map.entry("/sys/role",          new Rule(102, false)),
            Map.entry("/sys/menu",          new Rule(103, false)),
            // 机构树是各业务页下拉的字典数据(批次下达单位/更正乡镇/花名册等)：GET 开放，增删改仍需安全管理菜单
            Map.entry("/sys/org",           new Rule(104, true)),
            // ===== 系统设置 =====
            Map.entry("/setup/bank",        new Rule(201, true)),
            Map.entry("/setup/village",     new Rule(202, true)),
            Map.entry("/setup/beneficiary", new Rule(203, true)),
            // ===== 发放数据审核 =====
            Map.entry("/dept/audit",        new Rule(601, true)),
            // ===== 主管部门 =====
            Map.entry("/dept/project",      new Rule(301, true)),
            Map.entry("/dept/notice",       new Rule(305, true)),
            Map.entry("/dept/batch",        new Rule(306, true)),
            // ===== 花名册 =====
            Map.entry("/roster",            new Rule(401, true)),
            // ===== 集中支付 =====
            Map.entry("/pay/quota",         new Rule(501, true)),
            Map.entry("/pay/apply",         new Rule(502, true))
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;

        Long uid = UserContext.currentUserId();
        if (uid == null) return true;

        SysUser u = userMapper.selectById(uid);
        String ut = u == null ? null : u.getUserType();
        if ("SYS_ADMIN".equals(ut)) return true;

        String path = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }

        Rule rule = matchLongest(path);
        if (rule == null) return true;

        boolean isWrite = !("GET".equalsIgnoreCase(req.getMethod()) || "HEAD".equalsIgnoreCase(req.getMethod()));
        if (rule.writeOnly() && !isWrite) return true;

        if (!grantedMenuIds(uid).contains(rule.menuId())) {
            throw new BizException(403, "无权限访问该功能");
        }
        return true;
    }

    /** 取最长命中前缀，避免父前缀抢掉更精确的子前缀规则 */
    private Rule matchLongest(String path) {
        Rule best = null;
        int bestLen = -1;
        for (Map.Entry<String, Rule> e : RULES.entrySet()) {
            String p = e.getKey();
            if ((path.equals(p) || path.startsWith(p + "/")) && p.length() > bestLen) {
                best = e.getValue();
                bestLen = p.length();
            }
        }
        return best;
    }

    private Set<Long> grantedMenuIds(Long uid) {
        List<Long> roleIds = userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) return Set.of();
        return roleMenuMapper.selectList(
                        new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }
}
