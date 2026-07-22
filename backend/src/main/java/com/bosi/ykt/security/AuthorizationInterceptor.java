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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权拦截器（接在 {@link JwtInterceptor} 之后，UserContext 已就绪）。
 *
 * 模型：控制器基路径 -> 所需菜单 ID，复用 角色 → SYS_ROLE_MENU 的现有授权数据。
 *  - SYS_ADMIN：全量放行
 *  - writeOnly=false：任何方法都需对应菜单（≈ 管理员专属，如安全管理）
 *  - writeOnly=true：GET/HEAD 放行，写操作需对应菜单
 *  - 未命中：放行（/auth、/dashboard、/files 等公共接口）
 *
 * 匹配基于 {@link HandlerMethod} 所属控制器类的 {@code @RequestMapping} 基路径做精确匹配，
 * 而非 {@code getRequestURI()} 字符串前缀——后者对 URL 编码/多斜杠/矩阵参数(;)不规范化，
 * 存在「拦截器判为公共放行、Spring 路由却分发到受保护控制器」的越权绕过面。
 * 用已解析的处理器映射判权，从根上消除该类绕过。
 */
@Component
@RequiredArgsConstructor
public class AuthorizationInterceptor implements HandlerInterceptor {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    private record Rule(long menuId, boolean writeOnly) {}

    /** 控制器基路径(@RequestMapping) -> 规则。精确匹配，键须与各控制器 @RequestMapping 值一致。 */
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
            // 引用请求审批流：GET(待审列表) 开放、写(提交/确认/拒绝) 须补贴对象维护菜单 203；数据面由 DataScope 收窄到本辖区
            Map.entry("/setup/refer-request", new Rule(203, true)),
            // ===== 发放数据审核 =====
            Map.entry("/dept/audit",        new Rule(601, true)),
            // ===== 主管部门 =====
            Map.entry("/dept/project",      new Rule(301, true)),
            Map.entry("/dept/project-policy", new Rule(302, true)),
            Map.entry("/dept/policy",       new Rule(310, true)),
            Map.entry("/dept/notice",       new Rule(305, true)),
            Map.entry("/dept/batch",        new Rule(306, true)),
            Map.entry("/dept/correction",   new Rule(308, true)),
            // 发放表定义：写操作须菜单 311（此前漏登记——启动自检 AuthCoverageCheck 就是防这种盲区的）
            Map.entry("/dept/tpl",          new Rule(311, true)),
            // ===== 花名册 =====
            Map.entry("/roster",            new Rule(401, true)),
            // 编制花名册写接口：挂隐藏菜单 403(/roster/edit, VISIBLE=0)，migrate_21 授 role 2/7
            Map.entry("/dept/roster",       new Rule(403, true)),
            // ===== 集中支付 =====
            Map.entry("/pay/quota",         new Rule(501, true)),
            Map.entry("/pay/apply",         new Rule(502, true))
    );

    /** 已登记的控制器基路径（供启动自检 {@link AuthCoverageCheck} 核对覆盖面）。 */
    static Set<String> ruleBasePaths() {
        return RULES.keySet();
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        // 非控制器处理器（静态资源 / 404 转发等）无 @RequestMapping，非我方 API，放行
        if (!(handler instanceof HandlerMethod hm)) return true;

        Long uid = UserContext.currentUserId();
        if (uid == null) return true;

        SysUser u = userMapper.selectById(uid);
        String ut = u == null ? null : u.getUserType();
        if ("SYS_ADMIN".equals(ut)) return true;

        Rule rule = RULES.get(controllerBasePath(hm));
        if (rule == null) return true;

        boolean isWrite = !("GET".equalsIgnoreCase(req.getMethod()) || "HEAD".equalsIgnoreCase(req.getMethod()));
        if (rule.writeOnly() && !isWrite) return true;

        if (!grantedMenuIds(uid).contains(rule.menuId())) {
            throw new BizException(403, "无权限访问该功能");
        }
        return true;
    }

    /** 处理器所属控制器类的 @RequestMapping 基路径（取第一个值）；无则返回 ""。 */
    private static String controllerBasePath(HandlerMethod hm) {
        RequestMapping rm = hm.getBeanType().getAnnotation(RequestMapping.class);
        return (rm != null && rm.value().length > 0) ? rm.value()[0] : "";
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
