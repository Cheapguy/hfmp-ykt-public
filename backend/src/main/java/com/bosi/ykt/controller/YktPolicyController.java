package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktPolicy;
import com.bosi.ykt.mapper.YktPolicyMapper;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 政策基础录入（主管部门）。CRUD + 废止；按 政策文号 / 政策标题 模糊筛选。 */
@RestController
@RequestMapping("/dept/policy")
@RequiredArgsConstructor
public class YktPolicyController extends BaseCrudController<YktPolicyMapper, YktPolicy> {

    private final YktPolicyMapper mapper;

    @Override protected YktPolicyMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktPolicy> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktPolicy> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        Object no = params.get("policyNo");
        Object title = params.get("title");
        if (no != null && !no.toString().isBlank()) w.like("POLICY_NO", no.toString().trim());
        if (title != null && !title.toString().isBlank()) w.like("TITLE", title.toString().trim());
        w.orderByDesc("ID");
        return w;
    }

    @Override
    public R<?> create(@RequestBody YktPolicy p) {
        validate(p);
        return super.create(p);
    }

    @Override
    public R<?> update(@RequestBody YktPolicy p) {
        validate(p);
        return super.update(p);
    }

    /** 必填校验：政策标题不可为空（前端历来传 title，此处兜底防接口直连建出无标题脏数据） */
    private void validate(YktPolicy p) {
        if (p.getTitle() == null || p.getTitle().isBlank()) throw new BizException("政策标题不能为空");
    }

    /** 废止：政策状态 → 2 */
    @PostMapping("/{id}/discard")
    public R<?> discard(@PathVariable Long id) {
        YktPolicy p = mapper.selectById(id);
        if (p == null) throw new BizException("政策不存在");
        p.setStatus("2");
        mapper.updateById(p);
        return R.ok();
    }
}
