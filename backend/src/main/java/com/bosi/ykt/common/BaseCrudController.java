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

    /**
     * 写入（改/删）越权兜底钩子：默认沿用读取校验（读得到即写得动）。
     * 目的是让"默认不安全的基类写接口"变为默认安全——子类只要覆写了 assertReadable，
     * 未覆写 update/delete 时也自动获得同等的写越权防护。
     * 注：delete 传入库中现存实体（按归属校验可靠）；update 传入的是请求体实体，
     *   若需按"库中原归属"判权（防传本县字段改别县 id），子类应直接覆写 update
     *   （补贴对象/村组/批次/项目均已如此），基类无法从泛型 E 通用取主键再回查。
     */
    protected void assertWritable(E entity) { assertReadable(entity); }

    @PostMapping
    public R<?> create(@RequestBody E entity) {
        getMapper().insert(entity);
        return R.ok(entity);
    }

    @PutMapping
    public R<?> update(@RequestBody E entity) {
        assertWritable(entity);
        getMapper().updateById(entity);
        return R.ok();
    }

    @DeleteMapping("/{id:\\d+}")
    public R<?> delete(@PathVariable Long id) {
        E e = getMapper().selectById(id);
        if (e != null) assertWritable(e);   // 现存实体按归属校验，防越权删除
        getMapper().deleteById(id);
        return R.ok();
    }
}
