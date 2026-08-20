package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.HtmlSanitizer;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktPolicy;
import com.bosi.ykt.mapper.YktPolicyMapper;
import com.bosi.ykt.security.DataScopeResolver;
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
    private final DataScopeResolver dataScope;

    @Override protected YktPolicyMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktPolicy> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktPolicy> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        // 县域隔离：本县自建 + 上级下发的公共政策（514/516 条是导入的省州政策，各县都要看）
        dataScope.applyCreatorCounty(w, "CREATE_BY");
        Object no = params.get("policyNo");
        Object title = params.get("title");
        if (no != null && !no.toString().isBlank()) w.like("POLICY_NO", no.toString().trim());
        if (title != null && !title.toString().isBlank()) w.like("TITLE", title.toString().trim());
        w.orderByDesc("ID");
        return w;
    }

    /**
     * 读取越权兜底：detail 按 id 直读也要过县域（列表已由 buildQuery 收窄）。
     * 口径同 applyCreatorCounty——本县自建或上级公共政策可读。
     */
    @Override
    protected void assertReadable(YktPolicy p) {
        if (!dataScope.creatorCountyReadable(p.getCreateBy()))
            throw new BizException("无权查看该政策（非本县数据）");
    }

    /** 写入越权兜底：只能动本县自建的政策，上级下发的公共政策谁也改不了（ALL 除外）。 */
    @Override
    protected void assertWritable(YktPolicy p) {
        dataScope.assertCreatorCounty(p.getCreateBy(), "该政策");
    }

    @Override
    public R<?> create(@RequestBody YktPolicy p) {
        validate(p);
        p.setContent(HtmlSanitizer.clean(p.getContent()));
        // 新建不校验归属：CREATE_BY 由 MetaObjectHandler 按登录态填，天然落本县
        getMapper().insert(p);
        return R.ok(p);
    }

    @Override
    public R<?> update(@RequestBody YktPolicy p) {
        validate(p);
        p.setContent(HtmlSanitizer.clean(p.getContent()));
        // 按**库中原行**判权：请求体里的 CREATE_BY 由客户端可控，拿它判等于自证清白。
        // 也因此不能走 super.update()——基类会拿请求体再判一次，而请求体通常不带 CREATE_BY，
        // 会被 assertCreatorCounty 当成「上级下发数据」误拦，合法修改反而失败。
        assertWritable(loadOwned(p.getId()));
        p.setCreateBy(null);   // 防随请求体篡改归属（MP updateById 忽略 null 字段）
        getMapper().updateById(p);
        return R.ok();
    }

    /** 取库中原行；不存在按越权处理，防直连传别县 id 探测存在性。 */
    private YktPolicy loadOwned(Long id) {
        YktPolicy old = id == null ? null : mapper.selectById(id);
        if (old == null) throw new BizException("政策不存在");
        return old;
    }

    /** 必填校验：政策标题不可为空（前端历来传 title，此处兜底防接口直连建出无标题脏数据） */
    private void validate(YktPolicy p) {
        if (p.getTitle() == null || p.getTitle().isBlank()) throw new BizException("政策标题不能为空");
    }

    /** 废止：政策状态 → 2 */
    @PostMapping("/{id}/discard")
    public R<?> discard(@PathVariable Long id) {
        YktPolicy p = loadOwned(id);
        assertWritable(p);   // 废止=写操作，同样只能动本县自建的
        p.setStatus("2");
        mapper.updateById(p);
        return R.ok();
    }
}
