package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.FormulaEngine;
import com.bosi.ykt.common.R;
import com.bosi.ykt.common.TplService;
import com.bosi.ykt.entity.YktGrantDetail;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktTplItem;
import com.bosi.ykt.mapper.YktGrantDetailMapper;
import com.bosi.ykt.mapper.YktProjectMapper;
import com.bosi.ykt.mapper.YktTplItemMapper;
import com.bosi.ykt.security.DataScopeResolver;
import com.bosi.ykt.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 发放表定义（主管部门）。对生产「支付项目管理→清册样式定义」的裁剪版：
 * 每个项目一套清册导入模板列定义；固定列不可删/不可改标识类型绑定，自由列可增删改、可绑定明细字段。
 * 项目未初始化自定义列时展示内置默认 18 列（只读），初始化后方可编辑。
 */
@RestController
@RequestMapping("/dept/tpl")
@RequiredArgsConstructor
public class YktTplController {

    private final YktTplItemMapper tplItemMapper;
    private final YktProjectMapper projectMapper;
    private final YktGrantDetailMapper grantDetailMapper;
    private final TplService tplService;
    private final DataScopeResolver dataScope;

    /** 标识：小写字母开头，小写英数下划线，≤20（对齐生产数据项校验） */
    private static final Pattern KEY_FMT = Pattern.compile("^[a-z][a-z0-9_]{0,19}$");
    private static final Set<String> COL_TYPES = Set.of("text", "int", "decimal", "date", "idcard", "enum");
    /** 自由列清册显示分组：户主信息/收款人信息/补贴对象信息/发放信息/扩展信息 */
    private static final Set<String> COL_GROUPS = Set.of("HOLDER", "PAYEE", "BENEFICIARY", "GRANT", "EXT");

    /** 县域越权兜底：项目须在本人项目可见范围内（复用读取面同款前缀规则） */
    private void assertProject(Long projectId) {
        if (projectId == null) throw new BizException("projectId 不能为空");
        QueryWrapper<YktProject> w = new QueryWrapper<>();
        w.eq("ID", projectId);
        dataScope.applyProject(w, "PROJECT_CODE");
        if (projectMapper.selectCount(w) == 0) throw new BizException("项目不存在或无权操作");
    }

    /**
     * 项目是否已产生清册明细。清册网格整表按模板定义驱动、自由列值按 itemKey 存 EXT_JSON——
     * 明细落地后再删列/改标识/改类型/换模板，历史清册该列直接失联（数据在库里但永远不可见）。
     */
    private boolean hasDetails(Long projectId) {
        return grantDetailMapper.selectCount(new QueryWrapper<YktGrantDetail>()
                .inSql("BATCH_ID", "SELECT ID FROM YKT_BATCH WHERE PROJECT_ID = " + projectId)) > 0;
    }

    /** 项目列定义：custom=false 时 items 为内置默认列（只读展示，须先初始化才能编辑） */
    @GetMapping("/items")
    public R<Map<String, Object>> items(@RequestParam Long projectId) {
        assertProject(projectId);
        List<YktTplItem> custom = tplService.customOf(projectId);
        boolean isCustom = !custom.isEmpty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("custom", isCustom);
        m.put("items", isCustom ? custom : TplService.defaults());
        return R.ok(m);
    }

    /** 可绑定明细字段（绑定后数据类型被强制） */
    @GetMapping("/bind-options")
    public R<List<Map<String, String>>> bindOptions() {
        List<Map<String, String>> l = new ArrayList<>();
        TplService.BIND_FIELDS.forEach((k, v) ->
                l.add(Map.of("field", k, "label", v[0], "type", v[1])));
        return R.ok(l);
    }

    /** 从默认 18 列初始化项目自定义定义（已初始化则拒，防覆盖已编辑内容）。初始化结果=默认列本身，不改变历史清册显示，故不受明细锁限制。 */
    @PostMapping("/init")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> init(@RequestParam Long projectId) {
        assertProject(projectId);
        if (!tplService.customOf(projectId).isEmpty())
            throw new BizException("该项目已初始化模板定义，请直接编辑");
        for (YktTplItem t : TplService.defaults()) {
            t.setProjectId(projectId);
            fillAudit(t);
            tplItemMapper.insert(t);
        }
        return R.ok(null);
    }

    /** 恢复默认：清空项目自定义列，回落内置默认模板 */
    @PostMapping("/reset")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> reset(@RequestParam Long projectId) {
        assertProject(projectId);
        if (hasDetails(projectId))
            throw new BizException("该项目已产生清册明细数据，恢复默认会使历史清册丢失自定义列的显示，不允许恢复");
        tplItemMapper.delete(new QueryWrapper<YktTplItem>().eq("PROJECT_ID", projectId));
        return R.ok(null);
    }

    /** 新增/修改列。固定列仅允许改列名/排序/备注；绑定字段强制数据类型且项目内不可重复绑定。 */
    @PostMapping("/item")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> save(@RequestBody YktTplItem in) {
        assertProject(in.getProjectId());
        List<YktTplItem> custom = tplService.customOf(in.getProjectId());
        if (custom.isEmpty()) throw new BizException("该项目未初始化模板定义，请先初始化");

        YktTplItem db = null;
        if (in.getId() != null) {
            db = tplItemMapper.selectById(in.getId());
            if (db == null || !db.getProjectId().equals(in.getProjectId()))
                throw new BizException("列定义不存在");
        }
        boolean fixed = db != null && Integer.valueOf(1).equals(db.getFixedFlag());
        if (fixed) {
            // 固定列锁死标识/类型/绑定/必填，防把送审与发放链路依赖的字段改断
            in.setItemKey(db.getItemKey());
            in.setColType(db.getColType());
            in.setBindField(db.getBindField());
            in.setRequiredFlag(db.getRequiredFlag());
            in.setFixedFlag(1);
        } else {
            in.setFixedFlag(0);
        }

        String key = in.getItemKey() == null ? "" : in.getItemKey().trim();
        if (!KEY_FMT.matcher(key).matches())
            throw new BizException("标识必须以小写字母开头，只能含小写英文、数字、下划线，且不超过20位");
        in.setItemKey(key);
        String label = in.getItemLabel() == null ? "" : in.getItemLabel().trim();
        if (label.isEmpty() || label.length() > 60) throw new BizException("列名必填且不超过60个字符");
        in.setItemLabel(label);

        String bind = in.getBindField() == null ? "" : in.getBindField().trim();
        if (!bind.isEmpty()) {
            String[] bf = TplService.BIND_FIELDS.get(bind);
            if (bf == null) throw new BizException("绑定字段不合法：" + bind);
            in.setColType(bf[1]);   // 绑定即强制类型（对齐生产）
            in.setBindField(bind);
        } else {
            in.setBindField(null);
        }
        if (in.getColType() == null || !COL_TYPES.contains(in.getColType()))
            throw new BizException("数据类型不合法（text/int/decimal/date/idcard/enum）");
        // 清册显示分组：仅自由列有意义（绑定列跟随系统字段所在组），缺省进扩展信息
        // 注意 Set.of 的 contains(null) 抛 NPE，必须先判空
        if (in.getBindField() != null) in.setColGroup(null);
        else in.setColGroup(in.getColGroup() != null && COL_GROUPS.contains(in.getColGroup()) ? in.getColGroup() : "EXT");
        // 生命周期锁：已有清册明细后，改标识/类型/绑定会使历史数据失联或错位（新增列、改列名/分组/顺序/备注/枚举值/公式不受限）
        if (db != null && !fixed && hasDetails(in.getProjectId())) {
            boolean structChanged = !db.getItemKey().equals(in.getItemKey())
                    || !db.getColType().equals(in.getColType())
                    || !Objects.equals(db.getBindField(), in.getBindField());
            if (structChanged)
                throw new BizException("该项目已产生清册明细数据，不允许修改列的标识/数据类型/绑定字段"
                        + "（历史清册数据会失联或错位），仅可调整列名、分组、顺序、备注等显示属性");
        }
        // 枚举列（对生产“引用数据类型=枚举”）：归一化允许值（中英文逗号/顿号分隔，去空去重）
        if ("enum".equals(in.getColType())) {
            String[] parts = (in.getEnumValues() == null ? "" : in.getEnumValues())
                    .replace('，', ',').replace('、', ',').split(",");
            LinkedHashSet<String> vals = new LinkedHashSet<>();
            for (String s : parts) if (!s.trim().isEmpty()) vals.add(s.trim());
            if (vals.isEmpty()) throw new BizException("枚举类型必须填写允许值（逗号分隔，如 火车,汽车,飞机）");
            String joined = String.join(",", vals);
            if (joined.length() > 400) throw new BizException("枚举允许值总长不能超过400字符");
            in.setEnumValues(joined);
        } else {
            in.setEnumValues(null);
        }
        in.setRequiredFlag(Integer.valueOf(1).equals(in.getRequiredFlag()) ? 1 : 0);

        // 计算公式（对生产“清册样式公式”，如 金额=标准×月数）：仅数值列可设，导入时按行计算、忽略单元格
        String formula = in.getFormula() == null ? "" : FormulaEngine.normalize(in.getFormula());
        if (!formula.isEmpty()) {
            if (!"int".equals(in.getColType()) && !"decimal".equals(in.getColType()))
                throw new BizException("仅整数/金额类型的列可设置计算公式");
            if (formula.length() > 200) throw new BizException("公式长度不能超过200字符");
            in.setFormula(formula);
        } else {
            in.setFormula(null);
        }

        // 项目内唯一：标识 / 列名(Excel 表头按列名匹配) / 绑定字段
        for (YktTplItem t : custom) {
            if (in.getId() != null && t.getId().equals(in.getId())) continue;
            if (t.getItemKey().equals(in.getItemKey())) throw new BizException("标识[" + key + "]已存在");
            if (t.getItemLabel().equals(in.getItemLabel())) throw new BizException("列名[" + label + "]已存在");
            if (in.getBindField() != null && in.getBindField().equals(t.getBindField()))
                throw new BizException("字段[" + TplService.BIND_FIELDS.get(bind)[0] + "]已被列[" + t.getItemLabel() + "]绑定");
        }

        // 公式全量校验（用“本次修改后的列集”）：改标识/改类型也可能弄断别的列的公式，一并拦
        List<YktTplItem> prospective = new ArrayList<>();
        for (YktTplItem t : custom) if (in.getId() == null || !t.getId().equals(in.getId())) prospective.add(t);
        prospective.add(in);
        validateFormulas(prospective);

        if (in.getSortNo() == null) {
            if (db != null) in.setSortNo(db.getSortNo());
            else in.setSortNo(custom.stream().map(YktTplItem::getSortNo).filter(Objects::nonNull)
                    .max(Integer::compareTo).orElse(0) + 1);
        }
        // 插入语义：目标序号已被占用（非本行）→ 该位置及之后的列整体后移一位
        boolean occupied = custom.stream().anyMatch(t ->
                (in.getId() == null || !t.getId().equals(in.getId())) && in.getSortNo().equals(t.getSortNo()));
        if (occupied) {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktTplItem> shift =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            shift.eq("PROJECT_ID", in.getProjectId()).ge("SORT_NO", in.getSortNo());
            if (in.getId() != null) shift.ne("ID", in.getId());
            shift.setSql("SORT_NO = SORT_NO + 1");
            tplItemMapper.update(null, shift);
        }
        if (db == null) {
            fillAudit(in);
            tplItemMapper.insert(in);
        } else {
            // updateById 忽略 null 字段，清空绑定/备注会静默不生效——显式 set 全部可编辑列
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktTplItem> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            uw.eq("ID", in.getId())
              .set("ITEM_KEY", in.getItemKey())
              .set("ITEM_LABEL", in.getItemLabel())
              .set("COL_TYPE", in.getColType())
              .set("REQUIRED_FLAG", in.getRequiredFlag())
              .set("BIND_FIELD", in.getBindField())
              .set("ENUM_VALUES", in.getEnumValues())
              .set("FORMULA", in.getFormula())
              .set("COL_GROUP", in.getColGroup())
              .set("SORT_NO", in.getSortNo())
              .set("REMARK", in.getRemark())
              .set("UPDATE_TIME", LocalDateTime.now());
            tplItemMapper.update(null, uw);
        }
        return R.ok(null);
    }

    /**
     * 从其他项目复制模板定义（对生产发放表定义【复制】按钮，跨项目版）。
     * 目标已有自定义时须显式 overwrite=1（前端二次确认后传），防手滑覆盖。
     */
    @PostMapping("/copy")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> copy(@RequestParam Long fromProjectId, @RequestParam Long toProjectId,
                        @RequestParam(defaultValue = "0") int overwrite) {
        assertProject(fromProjectId);
        assertProject(toProjectId);
        if (fromProjectId.equals(toProjectId)) throw new BizException("源项目与目标项目相同");
        if (hasDetails(toProjectId))
            throw new BizException("目标项目已产生清册明细数据，整套复制会使历史清册列错位，不允许复制；如需扩展请逐列新增");
        List<YktTplItem> src = tplService.customOf(fromProjectId);
        if (src.isEmpty()) throw new BizException("源项目未定义模板（使用默认模板），无可复制内容");
        if (!tplService.customOf(toProjectId).isEmpty()) {
            if (overwrite != 1) throw new BizException("目标项目已有模板定义，如需覆盖请确认");
            tplItemMapper.delete(new QueryWrapper<YktTplItem>().eq("PROJECT_ID", toProjectId));
        }
        for (YktTplItem s : src) {
            YktTplItem t = new YktTplItem();
            t.setProjectId(toProjectId);
            t.setItemKey(s.getItemKey());
            t.setItemLabel(s.getItemLabel());
            t.setColType(s.getColType());
            t.setEnumValues(s.getEnumValues());
            t.setFormula(s.getFormula());
            t.setColGroup(s.getColGroup());
            t.setRequiredFlag(s.getRequiredFlag());
            t.setBindField(s.getBindField());
            t.setFixedFlag(s.getFixedFlag());
            t.setSortNo(s.getSortNo());
            t.setRemark(s.getRemark());
            fillAudit(t);
            tplItemMapper.insert(t);
        }
        return R.ok(null);
    }

    /** 列上移/下移：与相邻列交换序号（dir=up|down） */
    @PostMapping("/move")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> move(@RequestParam Long id, @RequestParam String dir) {
        YktTplItem cur = tplItemMapper.selectById(id);
        if (cur == null) throw new BizException("列定义不存在");
        assertProject(cur.getProjectId());
        List<YktTplItem> all = tplService.customOf(cur.getProjectId());
        int i = -1;
        for (int k = 0; k < all.size(); k++) if (all.get(k).getId().equals(id)) { i = k; break; }
        int j = "up".equals(dir) ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= all.size()) return R.ok(null);   // 已在端点，无动作
        // 交换序号（历史数据可能撞号，顺带按当前列表顺序归一化 1..n）
        for (int k = 0; k < all.size(); k++) {
            int want = k == i ? j + 1 : (k == j ? i + 1 : k + 1);
            if (!Integer.valueOf(want).equals(all.get(k).getSortNo())) {
                com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<YktTplItem> uw =
                        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
                uw.eq("ID", all.get(k).getId()).set("SORT_NO", want);
                tplItemMapper.update(null, uw);
            }
        }
        return R.ok(null);
    }

    /** 删除自由列（固定列拒删） */
    @DeleteMapping("/item/{id}")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> delete(@PathVariable Long id) {
        YktTplItem db = tplItemMapper.selectById(id);
        if (db == null) throw new BizException("列定义不存在");
        assertProject(db.getProjectId());
        if (Integer.valueOf(1).equals(db.getFixedFlag()))
            throw new BizException("固定列[" + db.getItemLabel() + "]为系统必需字段，不允许删除");
        if (hasDetails(db.getProjectId()))
            throw new BizException("该项目已产生清册明细数据，删除列[" + db.getItemLabel()
                    + "]会使历史清册丢失该列显示，不允许删除");
        // 被别的列公式引用的列不可删，防公式悬空
        for (YktTplItem t : tplService.customOf(db.getProjectId())) {
            if (t.getId().equals(id) || t.getFormula() == null || t.getFormula().isEmpty()) continue;
            if (FormulaEngine.refs(t.getFormula()).contains(db.getItemKey()))
                throw new BizException("列[" + db.getItemLabel() + "]被列[" + t.getItemLabel()
                        + "]的公式（" + t.getFormula() + "）引用，请先调整其公式");
        }
        tplItemMapper.deleteById(id);
        return R.ok(null);
    }

    /** 公式合法性：语法可解析、引用的标识存在且为数值列、不引用自身/其他公式列（防循环） */
    private void validateFormulas(List<YktTplItem> all) {
        Map<String, YktTplItem> byKey = new HashMap<>();
        for (YktTplItem t : all) byKey.put(t.getItemKey(), t);
        for (YktTplItem t : all) {
            String f = t.getFormula();
            if (f == null || f.isEmpty()) continue;
            Set<String> refs;
            try { refs = FormulaEngine.refs(f); }
            catch (Exception e) { throw new BizException("列[" + t.getItemLabel() + "]的公式不合法：" + e.getMessage()); }
            if (refs.isEmpty()) throw new BizException("列[" + t.getItemLabel() + "]的公式未引用任何列标识（常量无需公式）");
            for (String k : refs) {
                if (k.equals(t.getItemKey())) throw new BizException("列[" + t.getItemLabel() + "]的公式不能引用自身");
                YktTplItem r = byKey.get(k);
                if (r == null) throw new BizException("列[" + t.getItemLabel() + "]的公式引用了不存在的列标识「" + k + "」");
                if (!"int".equals(r.getColType()) && !"decimal".equals(r.getColType()))
                    throw new BizException("列[" + t.getItemLabel() + "]的公式引用的「" + r.getItemLabel() + "」不是整数/金额类型");
                if (r.getFormula() != null && !r.getFormula().isEmpty())
                    throw new BizException("列[" + t.getItemLabel() + "]的公式不能引用另一个公式列「" + r.getItemLabel() + "」");
            }
        }
    }

    private void fillAudit(YktTplItem t) {
        t.setTenantId(UserContext.currentTenantId());
        t.setCreateBy(UserContext.currentUserId());
        t.setCreateTime(LocalDateTime.now());
        t.setUpdateTime(LocalDateTime.now());
    }
}
