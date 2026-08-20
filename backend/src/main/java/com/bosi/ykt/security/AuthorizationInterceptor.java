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
            // 补贴对象是 PII 本体（身份证/银行卡/电话）：读也必须要菜单 203。
            // 它不是各页共用的字典，只有补贴对象维护页在调；开放读 = 同县任意登录账号
            // 都能翻页 dump 全县身份证和卡号，县域隔离拦不住这种「本县内的越权」。
            Map.entry("/setup/beneficiary", new Rule(203, false)),
            // 引用请求审批流：读写都须补贴对象维护菜单 203（不能像 /sys/org 那样 writeOnly——
            // 它不是各页共用的字典，GET 返回身份证号，只被补贴对象维护页和乡镇工作台调用，开放读等于白给一个身份证读取口）
            Map.entry("/setup/refer-request", new Rule(203, false)),
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
            // 花名册两条路径同样直出身份证/卡号，同上：GET 也要菜单，不做 writeOnly
            Map.entry("/roster",            new Rule(401, false)),
            // 编制花名册：挂隐藏菜单 403(/roster/edit, VISIBLE=0)，migrate_21 授 role 2/7
            Map.entry("/dept/roster",       new Rule(403, false)),
            // ===== 集中支付 =====
            Map.entry("/pay/quota",         new Rule(501, true)),
            Map.entry("/pay/apply",         new Rule(502, true))
    );

    /**
     * 方法级覆盖：同一控制器里分属不同菜单的端点，键 = 控制器基路径 + "#" + 方法名，优先于控制器级规则。
     *
     * <p>补贴项目的审核链端点和维护端点同住 {@code /dept/project} 控制器，但分属两个菜单：
     * 维护(301) 归县财政录入岗，审核(312) 归省财政厅业务处室/农业处。若都按控制器级的 301 判，
     * 省厅两岗就得拿到「补贴项目维护」菜单才能审核——那等于顺带给了他们新增/删除县项目的入口。
     */
    private static final Map<String, Rule> METHOD_RULES = Map.of(
            "/dept/project#approve",   new Rule(312, true),
            "/dept/project#reject",    new Rule(312, true),
            "/dept/project#traceCode", new Rule(312, true)
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

        // 能走到这里的请求都过了 JwtInterceptor（无 token 直接 401），所以 uid 缺失只可能是
        // 一个 uid claim 为空的合法签名 token——这不是「公共接口」，按未登录拒掉，不再无条件放行
        Long uid = UserContext.currentUserId();
        if (uid == null) throw new BizException(401, "未登录");

        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException(401, "用户不存在");
        // 逐请求核对启用状态：JWT 是无状态的，禁用/删号在 token 过期前(默认 8h)不会自动失效
        if (u.getStatus() != null && u.getStatus() == 0) throw new BizException(401, "账号已禁用");

        if ("SYS_ADMIN".equals(u.getUserType())) return true;

        String base = controllerBasePath(hm);
        Rule rule = METHOD_RULES.get(base + "#" + hm.getMethod().getName());
        if (rule == null) rule = RULES.get(base);
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
