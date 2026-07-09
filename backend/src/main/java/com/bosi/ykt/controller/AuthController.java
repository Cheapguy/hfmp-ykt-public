package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysMenu;
import com.bosi.ykt.entity.SysRoleMenu;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.mapper.SysMenuMapper;
import com.bosi.ykt.mapper.SysRoleMenuMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import com.bosi.ykt.security.JwtUtil;
import com.bosi.ykt.security.UserContext;
import cn.hutool.crypto.digest.BCrypt;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final JwtUtil jwtUtil;
    private final com.bosi.ykt.security.LoginGuard loginGuard;

    /** 时序均衡用的假 bcrypt 串：账号不存在也照样跑一次校验，防以响应耗时枚举账号 */
    private static final String DUMMY_HASH = BCrypt.hashpw("timing-equalizer", BCrypt.gensalt());

    @Data
    public static class LoginReq {
        private String username;
        private String password;
        private String captcha;
        private String captchaKey;
    }

    /** 图形验证码（渐进式：登录连续失败 2 次后要求）。返回 {key, img} */
    @GetMapping("/captcha")
    public R<Map<String, String>> captcha() {
        return R.ok(loginGuard.newCaptcha());
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req) {
        if (req.getUsername() == null || req.getPassword() == null) {
            throw new BizException("请输入账号密码");
        }
        String uname = req.getUsername().trim();
        loginGuard.checkLock(uname);                          // 连续失败 5 次锁 10 分钟
        if (loginGuard.captchaRequired(uname)) {              // 连续失败 2 次起要验证码
            if (req.getCaptchaKey() == null || req.getCaptcha() == null || req.getCaptcha().isBlank())
                throw new BizException(com.bosi.ykt.security.LoginGuard.CODE_CAPTCHA_REQUIRED, "请输入验证码");
            if (!loginGuard.verifyCaptcha(req.getCaptchaKey(), req.getCaptcha())) {
                loginGuard.onFail(uname);   // 验证码错也计失败：防验证码爆破，且失败计数不会卡在阈值下
                throw new BizException(com.bosi.ykt.security.LoginGuard.CODE_CAPTCHA_REQUIRED, "验证码错误或已过期");
            }
        }

        SysUser u = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, uname).last("AND ROWNUM=1")
        );
        boolean ok = u != null && passwordMatches(req.getPassword(), u.getPassword());
        if (u == null) passwordMatches(req.getPassword(), DUMMY_HASH);   // 时序均衡
        if (!ok) {
            // 统一文案：不区分「账号不存在/密码错误」，防账号枚举
            loginGuard.onFail(uname);
            throw new BizException("用户名或密码错误");
        }
        if (u.getStatus() != null && u.getStatus() == 0) throw new BizException("账号已禁用");
        loginGuard.onSuccess(uname);

        u.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(u);

        String token = jwtUtil.issue(u.getId(), u.getUsername(), u.getTenantId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", u.getId());
        data.put("username", u.getUsername());
        data.put("realName", u.getRealName());
        data.put("userType", u.getUserType());
        return R.ok(data);
    }

    @GetMapping("/info")
    public R<Map<String, Object>> info() {
        Long uid = UserContext.currentUserId();
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException(401, "用户不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("userId", u.getId());
        data.put("username", u.getUsername());
        data.put("realName", u.getRealName());
        data.put("userType", u.getUserType());
        data.put("phone", u.getPhone());
        data.put("email", u.getEmail());
        return R.ok(data);
    }

    @GetMapping("/menus")
    public R<List<SysMenu>> menus() {
        // 不再按 visible 过滤：隐藏菜单(如 403 /roster/edit)与 F 按钮权限也要下发，
        // 供前端路由守卫/按钮级权限判定；侧边栏渲染由前端按 visible=1 且非 F 过滤
        List<SysMenu> all = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSortNo)
        );

        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        String ut = u == null ? null : u.getUserType();
        if ("SYS_ADMIN".equals(ut)) {
            return R.ok(all);
        }

        List<Long> roleIds = uid == null ? List.of() :
                userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                        .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) return R.ok(List.of());

        Set<Long> granted = roleMenuMapper.selectList(
                        new LambdaQueryWrapper<SysRoleMenu>().in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toCollection(HashSet::new));
        if (granted.isEmpty()) return R.ok(List.of());

        // 回填父级，避免子菜单因父节点缺失被前端建树丢弃
        Map<Long, Long> parentOf = all.stream()
                .collect(Collectors.toMap(SysMenu::getId, m -> m.getParentId() == null ? 0L : m.getParentId()));
        Set<Long> visibleIds = new HashSet<>(granted);
        for (Long id : granted) {
            Long p = parentOf.get(id);
            while (p != null && p != 0L && !visibleIds.contains(p)) {
                visibleIds.add(p);
                p = parentOf.get(p);
            }
        }
        List<SysMenu> filtered = all.stream().filter(m -> visibleIds.contains(m.getId())).toList();
        return R.ok(filtered);
    }

    /** bcrypt 串($2 开头)走 BCrypt 校验，否则按明文比对（脚手架种子用明文） */
    private boolean passwordMatches(String raw, String stored) {
        if (raw == null || stored == null) return false;
        if (stored.startsWith("$2")) {
            try { return BCrypt.checkpw(raw, stored); } catch (Exception e) { return false; }
        }
        return raw.equals(stored);
    }

    @PostMapping("/logout")
    public R<?> logout() { return R.ok(); }

    @PostMapping("/change-password")
    public R<?> changePwd(@RequestBody Map<String, String> body) {
        Long uid = UserContext.currentUserId();
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        if (!passwordMatches(oldPwd, u.getPassword())) throw new BizException("旧密码错误");
        if (newPwd == null || newPwd.length() < 6) throw new BizException("新密码至少6位");
        u.setPassword(BCrypt.hashpw(newPwd, BCrypt.gensalt()));
        userMapper.updateById(u);
        return R.ok();
    }
}
