package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.security.DataScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sys/org")
@RequiredArgsConstructor
public class SysOrgController extends BaseCrudController<SysOrgMapper, SysOrg> {

    private final SysOrgMapper mapper;
    private final DataScopeResolver dataScope;
    @Override protected SysOrgMapper getMapper() { return mapper; }

    @GetMapping("/tree")
    public R<List<SysOrg>> tree() {
        LambdaQueryWrapper<SysOrg> w = new LambdaQueryWrapper<>();
        dataScope.applyOrgTree(w);   // 县域裁剪：管理员 ALL 不过滤，县/乡镇按范围收窄
        w.orderByAsc(SysOrg::getSortNo);
        return R.ok(mapper.selectList(w));
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        Long children = mapper.selectCount(new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getParentId, id));
        if (children != null && children > 0) return R.fail("存在下级机构，无法删除");
        mapper.deleteById(id);
        return R.ok();
    }
}
