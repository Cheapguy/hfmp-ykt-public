package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysRole;
import com.bosi.ykt.entity.SysRoleMenu;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.mapper.SysRoleMapper;
import com.bosi.ykt.mapper.SysRoleMenuMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/sys/role")
@RequiredArgsConstructor
public class SysRoleController extends BaseCrudController<SysRoleMapper, SysRole> {

    private final SysRoleMapper mapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    @Override protected SysRoleMapper getMapper() { return mapper; }

    /** 安全管理四个菜单：用户/角色/菜单/机构。挂上它们等于拿到造账号造角色的能力。 */
    private static final Set<Long> ADMIN_ONLY_MENUS = Set.of(101L, 102L, 103L, 104L);

    // ===== 提权防线 =====
    // 用户侧那三条守卫只挡住「把我没有的角色发给别人」。角色本身才是权限的定义处：
    // 有菜单 102 的人若能随手把自己已持有角色的 DATA_SCOPE 改成 ALL，或给它挂上 101-104，
    // 不必造一个 SYS_ADMIN 就拿到了等效能力。种子里 102 只授给了超管，属于「配置恰好安全」，
    // 这里把它变成代码保证。

    private boolean currentIsAdmin() {
        Long uid = UserContext.currentUserId();
        if (uid == null) return false;
        SysUser me = userMapper.selectById(uid);
        return me != null && "SYS_ADMIN".equals(me.getUserType());
    }

    /** 全域数据范围只有超管能授。null=没改这一列（MP 忽略 null），不拦。 */
    private void assertScopeAssignable(String dataScope) {
        if (dataScope == null) return;
        if (("ALL".equals(dataScope) || "STATE".equals(dataScope)) && !currentIsAdmin())
            throw new BizException(403, "无权将角色数据范围设为全域");
    }

    private void assertMenusAssignable(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty() || currentIsAdmin()) return;
        for (Long mid : menuIds) {
            if (mid != null && ADMIN_ONLY_MENUS.contains(mid))
                throw new BizException(403, "无权为角色分配安全管理菜单");
        }
    }

    @Override
    public R<?> create(@RequestBody SysRole r) {
        assertScopeAssignable(r.getDataScope());
        return super.create(r);
    }

    @Override
    public R<?> update(@RequestBody SysRole r) {
        assertScopeAssignable(r.getDataScope());
        return super.update(r);
    }

    @GetMapping("/{id}/menu-ids")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(roleMenuMapper.selectList(
                        new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id))
                .stream().map(SysRoleMenu::getMenuId).toList());
    }

    @PutMapping("/{id}/menus")
    public R<?> assignMenus(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        List<Long> menuIds = body.getOrDefault("menuIds", List.of());
        assertMenusAssignable(menuIds);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        for (Long mid : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(id);
            rm.setMenuId(mid);
            roleMenuMapper.insert(rm);
        }
        return R.ok();
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        Long bound = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (bound != null && bound > 0)
            throw new BizException("该角色下仍有 " + bound + " 个用户，请先解绑再删除");
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        mapper.deleteById(id);
        return R.ok();
    }
}
