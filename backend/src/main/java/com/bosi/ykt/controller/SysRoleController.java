package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysRole;
import com.bosi.ykt.entity.SysRoleMenu;
import com.bosi.ykt.mapper.SysRoleMapper;
import com.bosi.ykt.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sys/role")
@RequiredArgsConstructor
public class SysRoleController extends BaseCrudController<SysRoleMapper, SysRole> {

    private final SysRoleMapper mapper;
    private final SysRoleMenuMapper roleMenuMapper;
    @Override protected SysRoleMapper getMapper() { return mapper; }

    @GetMapping("/{id}/menu-ids")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(roleMenuMapper.selectList(
                        new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id))
                .stream().map(SysRoleMenu::getMenuId).toList());
    }

    @PutMapping("/{id}/menus")
    public R<?> assignMenus(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        List<Long> menuIds = body.getOrDefault("menuIds", List.of());
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
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        mapper.deleteById(id);
        return R.ok();
    }
}
