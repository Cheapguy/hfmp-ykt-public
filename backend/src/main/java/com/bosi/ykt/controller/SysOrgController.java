package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
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

    /**
     * page/list 也走县域裁剪。此前只有 /tree 过滤，`/sys/org/list` 是 writeOnly 的开放 GET，
     * 任何登录账号都能一次性拿到全州机构清单——同一份数据两个口径，等于给树加的那道过滤白加了。
     */
    @Override
    protected QueryWrapper<SysOrg> buildQuery(java.util.Map<String, Object> params) {
        QueryWrapper<SysOrg> w = super.buildQuery(params);
        dataScope.applyOrgTree(w);
        return w;
    }

    /** detail 读取越权兜底：禁止凭 id 越权读别县机构。 */
    @Override
    protected void assertReadable(SysOrg e) {
        if (!dataScope.orgVisible(e)) throw new BizException("无权查看该机构（非本县数据）");
    }

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
