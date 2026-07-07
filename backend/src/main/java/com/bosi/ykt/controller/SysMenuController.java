package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysMenu;
import com.bosi.ykt.mapper.SysMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sys/menu")
@RequiredArgsConstructor
public class SysMenuController extends BaseCrudController<SysMenuMapper, SysMenu> {

    private final SysMenuMapper mapper;
    @Override protected SysMenuMapper getMapper() { return mapper; }

    @GetMapping("/tree")
    public R<List<SysMenu>> tree() {
        return R.ok(mapper.selectList(new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSortNo)));
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        Long children = mapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        if (children != null && children > 0) return R.fail("存在子菜单，无法删除");
        mapper.deleteById(id);
        return R.ok();
    }
}
