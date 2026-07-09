package com.bosi.ykt.common;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.security.UserContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通用 CRUD 基类控制器：page / list / detail / create / update / delete。
 * 子类只需暴露 getMapper()，并在类上标注 @RestController + @RequestMapping("/xxx")。
 * 默认按 UserContext 当前租户隔离；非多租户场景可覆写 buildQuery() 去掉 TENANT_ID 条件。
 */
public abstract class BaseCrudController<M extends BaseMapper<E>, E> {

    protected abstract M getMapper();

    protected QueryWrapper<E> buildQuery(Map<String, Object> params) {
        QueryWrapper<E> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        return w;
    }

    @GetMapping("/page")
    public R<IPage<E>> page(@RequestParam(defaultValue = "1") long pageNum,
                            @RequestParam(defaultValue = "10") long pageSize,
                            @RequestParam Map<String, Object> params) {
        Page<E> p = new Page<>(pageNum, pageSize);
        QueryWrapper<E> w = buildQuery(params);
        return R.ok(getMapper().selectPage(p, w));
    }

    @GetMapping("/list")
    public R<List<E>> list(@RequestParam Map<String, Object> params) {
        return R.ok(getMapper().selectList(buildQuery(params)));
    }

    @GetMapping("/{id:\\d+}")
    public R<E> detail(@PathVariable Long id) {
        E e = getMapper().selectById(id);
        if (e != null) assertReadable(e);   // 县域/租户越权兜底：page/list 有隔离，detail 按 id 必须同样过一遍
        return R.ok(e);
    }

    /**
     * 读取越权兜底钩子：默认放行（全局/管理类数据）。
     * 含县域隐私数据的子类（补贴对象/村组/批次/项目）必须 override，
     * 否则任何登录账号可凭 id 猜解越权读取（IDOR）。
     */
    protected void assertReadable(E entity) { }

    @PostMapping
    public R<?> create(@RequestBody E entity) {
        getMapper().insert(entity);
        return R.ok(entity);
    }

    @PutMapping
    public R<?> update(@RequestBody E entity) {
        getMapper().updateById(entity);
        return R.ok();
    }

    @DeleteMapping("/{id:\\d+}")
    public R<?> delete(@PathVariable Long id) {
        getMapper().deleteById(id);
        return R.ok();
    }
}
