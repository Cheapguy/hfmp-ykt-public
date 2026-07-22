package com.bosi.ykt.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.YktBank;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.entity.YktVillage;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.YktBankMapper;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import com.bosi.ykt.mapper.YktVillageMapper;
import com.bosi.ykt.security.UserContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.hssf.usermodel.DVConstraint;
import org.apache.poi.hssf.usermodel.HSSFDataValidation;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 补贴对象库。手册 §八：新增/修改/删除/导入/导出/引用/注销/批量修改 */
@RestController
@RequestMapping("/setup/beneficiary")
@RequiredArgsConstructor
public class YktBeneficiaryController extends BaseCrudController<YktBeneficiaryMapper, YktBeneficiary> {
    private final YktBeneficiaryMapper mapper;
    private final YktVillageMapper villageMapper;
    private final YktBankMapper bankMapper;
    private final SysUserMapper userMapper;
    private final SysOrgMapper orgMapper;
    private final com.bosi.ykt.security.DataScopeResolver dataScope;
    @Override protected YktBeneficiaryMapper getMapper() { return mapper; }

    @Override
    protected QueryWrapper<YktBeneficiary> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktBeneficiary> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        like(w, "NAME", params.get("name"));
        like(w, "ID_CARD", params.get("idCard"));
        like(w, "HEAD_NAME", params.get("headName"));
        like(w, "HEAD_ID_CARD", params.get("headIdCard"));
        like(w, "BANK_ACCOUNT", params.get("bankAccount"));
        eq(w, "TOWN_ID", params.get("townId"));
        eq(w, "VILLAGE_ID", params.get("villageId"));
        eq(w, "IS_RURAL", params.get("isRural"));
        dataScope.applyTown(w, "TOWN_ID");   // 县域隔离
        w.orderByAsc("ID");
        return w;
    }

    private static void like(QueryWrapper<YktBeneficiary> w, String col, Object v) {
        if (v != null && !"".equals(v)) w.like(col, v);
    }

    private static void eq(QueryWrapper<YktBeneficiary> w, String col, Object v) {
        if (v != null && !"".equals(v)) w.eq(col, v);
    }

    /**
     * 补贴对象归属乡镇由村(居)委会唯一决定：前端新增/修改表单只选村委会(villageId)不选乡镇，
     * 这里按 villageId 回查回填 townId——否则 townId 为空会被县域校验误判「非本县数据」，且落库后过滤永远查不到。
     */
    private void fillTownFromVillage(YktBeneficiary e) {
        if (e.getVillageId() == null) return;
        YktVillage v = villageMapper.selectById(e.getVillageId());
        if (v == null) throw new com.bosi.ykt.common.BizException("村(居)委会不存在");
        e.setTownId(v.getTownId());
    }

    /** 县域越权兜底（写路径）：委托 DataScopeResolver 单一真源。 */
    private void assertTownScope(Long townId) {
        dataScope.assertTown(townId, "该补贴对象");
    }

    /** 按 id 兜底：反查现存记录的乡镇后校验。 */
    private YktBeneficiary assertExisting(Long id) {
        YktBeneficiary b = id == null ? null : mapper.selectById(id);
        if (b == null) throw new com.bosi.ykt.common.BizException("补贴对象不存在");
        assertTownScope(b.getTownId());
        return b;
    }

    /** detail 读取越权兜底：补贴对象含身份证/银行卡号，禁止跨县凭 id 越权读。 */
    @Override
    protected void assertReadable(YktBeneficiary e) { assertTownScope(e.getTownId()); }

    /** 归属辖区展示（对齐生产文案）：如「甲县-甲县一号镇人民政府」；机构缺失时降级。 */
    private String ownerLabel(Long townId) {
        SysOrg town = townId == null ? null : orgMapper.selectById(townId);
        if (town == null) return "未知辖区";
        String code = town.getOrgCode();
        if (code != null && code.length() >= 6) {
            SysOrg county = orgMapper.selectOne(new QueryWrapper<SysOrg>()
                    .eq("ORG_CODE", code.substring(0, 6)).eq("ORG_TYPE", "COUNTY").last("AND ROWNUM=1"));
            if (county != null) return county.getOrgName() + "-" + town.getOrgName();
        }
        return town.getOrgName();
    }

    /** 全库按身份证查现存对象（跨辖区也算重复，报错要指明现存记录归属谁的辖区）。 */
    private YktBeneficiary findByIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) return null;
        return mapper.selectOne(new QueryWrapper<YktBeneficiary>()
                .eq("ID_CARD", idCard.trim()).last("AND ROWNUM=1"));
    }

    /** 列表：附带把 createBy(用户 id) 反查显示名，避免前端直接展示 700031 这类裸 id。 */
    @Override
    @GetMapping("/page")
    public R<com.baomidou.mybatisplus.core.metadata.IPage<YktBeneficiary>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam Map<String, Object> params) {
        R<com.baomidou.mybatisplus.core.metadata.IPage<YktBeneficiary>> r = super.page(pageNum, pageSize, params);
        com.baomidou.mybatisplus.core.metadata.IPage<YktBeneficiary> pg = r.getData();
        List<YktBeneficiary> rows = pg == null ? null : pg.getRecords();
        if (rows != null && !rows.isEmpty()) {
            Set<Long> ids = new HashSet<>();
            for (YktBeneficiary b : rows) if (b.getCreateBy() != null) ids.add(b.getCreateBy());
            if (!ids.isEmpty()) {
                Map<Long, String> disp = new HashMap<>();
                for (SysUser u : userMapper.selectBatchIds(ids))
                    disp.put(u.getId(), (u.getRealName() == null || u.getRealName().isBlank()) ? u.getUsername() : u.getRealName());
                for (YktBeneficiary b : rows)
                    b.setCreateByName(b.getCreateBy() == null ? null : disp.get(b.getCreateBy()));
            }
        }
        return r;
    }

    @Override
    public R<?> create(@org.springframework.web.bind.annotation.RequestBody YktBeneficiary e) {
        fillTownFromVillage(e);          // 前端表单只选村委会，按村委会推乡镇归属
        assertTownScope(e.getTownId());
        // 引用建档（referred=1，人户迁移复制建档）放行同证复制，普通新增全库查重
        if (!Integer.valueOf(1).equals(e.getReferred())) {
            YktBeneficiary d = findByIdCard(e.getIdCard());
            if (d != null) throw new BizException("家庭成员姓名：" + d.getName() + "，身份证号码：" + d.getIdCard()
                    + " 在系统中(" + ownerLabel(d.getTownId()) + ")已存在！");
        }
        return super.create(e);
    }

    @Override
    public R<?> update(@org.springframework.web.bind.annotation.RequestBody YktBeneficiary e) {
        if (e.getId() == null) throw new com.bosi.ykt.common.BizException("缺少 id");
        YktBeneficiary old = assertExisting(e.getId());
        fillTownFromVillage(e);          // 改了村委会则同步回填新乡镇归属（未改村委会 villageId 为空不动 townId）
        if (e.getTownId() != null) assertTownScope(e.getTownId());   // 改乡镇归属也须在本县内
        // 仅当把身份证号改成了别的号时查重（不改号的常规编辑不受已有引用副本影响）
        if (e.getIdCard() != null && !e.getIdCard().isBlank()
                && !e.getIdCard().trim().equals(old.getIdCard())) {
            YktBeneficiary d = findByIdCard(e.getIdCard());
            if (d != null && !d.getId().equals(e.getId()))
                throw new BizException("家庭成员姓名：" + d.getName() + "，身份证号码：" + d.getIdCard()
                        + " 在系统中(" + ownerLabel(d.getTownId()) + ")已存在！");
        }
        return super.update(e);
    }

    @Override
    public R<?> delete(@PathVariable Long id) {
        YktBeneficiary b = assertExisting(id);
        // 引用关系保护：本记录是「原始建档」（未标记 referred=1），且已被其他乡镇引用建档（存在同证 referred=1 副本）时，
        // 必须由引用乡镇先删除引用副本（副本 referred=1 可直接删）后，原乡镇才能删除。提示被引用到的具体乡镇。
        if (!Integer.valueOf(1).equals(b.getReferred()) && b.getIdCard() != null && !b.getIdCard().isBlank()) {
            List<YktBeneficiary> refs = mapper.selectList(new QueryWrapper<YktBeneficiary>()
                    .eq("ID_CARD", b.getIdCard().trim()).eq("REFERRED", 1).ne("ID", id));
            if (!refs.isEmpty()) {
                String towns = refs.stream().map(r -> ownerLabel(r.getTownId()))
                        .distinct().collect(java.util.stream.Collectors.joining("、"));
                throw new BizException("家庭成员：" + nz(b.getName()) + "，身份证号码：" + b.getIdCard()
                        + " 已被【" + towns + "】引用，请先由对应乡镇删除引用后，本乡镇再删除！");
            }
        }
        return super.delete(id);
    }

    /**
     * 引用：按身份证号全系统检索补贴对象（人户迁移/跨乡镇发放）。手册 §八-6
     * 检索面放开为「系统内检索」是刻意的（人户迁移要跨乡镇）；但返回体只回**身份核对 + 落地所需的非敏感字段**，
     * 银行账号 / 社保卡 / 公共账户 / 电话一律不下发——否则任意登录账号拿身份证即可枚举全省银行账号。
     * 真正复制建档要用的敏感字段，在「纳入」时由后台从原始档（sourceId）拷，不经客户端往返。
     */
    @GetMapping("/refer")
    public R<YktBeneficiary> refer(@RequestParam String idCard) {
        // 注意：Oracle ROWNUM 与 ORDER BY 不能靠 last() 拼（last 追加在 ORDER BY 之后语法非法），
        // 且 11g 无 FETCH FIRST——改 SQL 端按 REFERRED 升序（原始 0 优先于副本 1）取全部，Java 取第一条。
        List<YktBeneficiary> list = mapper.selectList(new QueryWrapper<YktBeneficiary>()
                .eq("ID_CARD", idCard).orderByAsc("REFERRED"));
        if (list.isEmpty()) return R.ok(null);
        YktBeneficiary src = list.get(0);
        YktBeneficiary view = new YktBeneficiary();   // 敏感字段脱敏：只投影下列，其余（bankAccount/socialCard/公共账户/phone）不出库
        view.setId(src.getId());
        view.setHouseholdCode(src.getHouseholdCode());
        view.setHeadName(src.getHeadName());
        view.setName(src.getName());
        view.setIdCard(src.getIdCard());
        view.setTownId(src.getTownId());
        view.setVillageId(src.getVillageId());
        view.setGroupName(src.getGroupName());
        view.setGender(src.getGender());
        view.setRelation(src.getRelation());
        view.setReferred(src.getReferred());
        view.setTownLabel(ownerLabel(src.getTownId()));   // 「县-乡镇」跨县也能显示，前端下拉是县域收窄的算不了
        return R.ok(view);
    }

    /** 注销。手册 §八-7（状态置 0） */
    @PostMapping("/{id}/cancel")
    public R<?> cancel(@PathVariable Long id) {
        assertExisting(id);
        YktBeneficiary b = new YktBeneficiary();
        b.setId(id);
        b.setStatus("0");
        mapper.updateById(b);
        return R.ok();
    }

    /** 取消注销（状态恢复 1） */
    @PostMapping("/{id}/uncancel")
    public R<?> uncancel(@PathVariable Long id) {
        assertExisting(id);
        YktBeneficiary b = new YktBeneficiary();
        b.setId(id);
        b.setStatus("1");
        mapper.updateById(b);
        return R.ok();
    }

    // ==================== 导入 / 导出（对齐生产 24 列口径） ====================

    /** 导入/导出表头（对齐生产 补贴对象维护 导出文件 24 列） */
    private static final List<String> HEADERS = List.of(
            "状态", "农户编码", "户主姓名", "户主身份证号", "家庭成员姓名", "身份证号", "村(居)委会", "村（居）民小组",
            "性别", "联系电话", "年龄", "与户主关系", "账户名称", "银行账号", "开户银行", "公共账户名称", "公共账户账号",
            "公共账户开户行", "是否低保户", "是否乡村人口", "是否建档立卡户", "是否特困人员", "备注", "创建人");

    /** 必填列（参照 import.xls：有数据的列均必填） */
    private static final Set<String> REQUIRED = Set.of(
            "农户编码", "户主姓名", "户主身份证号", "家庭成员姓名", "身份证号",
            "村(居)委会", "村（居）民小组", "与户主关系", "账户名称", "银行账号", "开户银行");

    /**
     * 与户主关系 34 项（与前端 人员新增/批量修改 的 RELATION_OPTS 同源，存纯文本）。
     * 导入认「本人」或「01-本人」两种写法；历史种子数据里的 8 项老字典文本（本人或户主/子女…）仅存量展示，不再允许导入。
     */
    public static final List<String> RELATIONS = List.of(
            "本人", "妻子", "兄弟", "姐妹", "父亲", "母亲", "外婆", "外公",
            "儿子", "女儿", "孙子", "孙女", "丈夫", "祖父", "祖母", "儿媳",
            "女婿", "嫂子", "弟媳", "舅舅", "外甥", "姑嫂", "伯叔", "伯母",
            "侄子", "侄女", "孙女婿", "孙媳", "重孙", "婆婆", "公公", "爸爸",
            "奶奶", "其他");

    /** 与户主关系候选（前端表单/下拉同源） */
    @GetMapping("/relations")
    public R<List<String>> relations() {
        return R.ok(RELATIONS);
    }

    /**
     * 导入模板（.xls，仅表头）。「与户主关系」列做 Excel 数据有效性下拉（34 项），
     * 只能从下拉选择，手输非法值被 Excel 拒（STOP 级错误框）。
     */
    @GetMapping("/import-template")
    public void importTemplate(HttpServletResponse resp) throws Exception {
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet sh = wb.createSheet("补贴对象");
            Row head = sh.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                head.createCell(i).setCellValue(HEADERS.get(i));
                sh.setColumnWidth(i, 18 * 256);
            }
            int relCol = HEADERS.indexOf("与户主关系");
            DVConstraint dv = DVConstraint.createExplicitListConstraint(RELATIONS.toArray(new String[0]));
            HSSFDataValidation validation = new HSSFDataValidation(
                    new CellRangeAddressList(1, 5000, relCol, relCol), dv);
            validation.setSuppressDropDownArrow(false);   // HSSF：false 才显示下拉箭头
            validation.setShowErrorBox(true);
            validation.createErrorBox("输入不合法", "「与户主关系」只能从下拉列表中选择，不允许手工输入其他值");
            sh.addValidationData(validation);
            resp.setContentType("application/vnd.ms-excel");
            resp.setCharacterEncoding("utf-8");
            String fn = java.net.URLEncoder.encode("补贴对象导入模板", java.nio.charset.StandardCharsets.UTF_8);
            resp.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fn + ".xls");
            wb.write(resp.getOutputStream());
        }
    }

    /** "code-name" → name（生产导出的 村(居)委会/开户银行 带编码前缀，导入两种格式都认） */
    private static String stripCodePrefix(String v) {
        int i = v.indexOf('-');
        return (i > 0 && v.substring(0, i).matches("\\d+")) ? v.substring(i + 1) : v;
    }

    /** "1-正常"/"1" → "1"；空 → def；非法返回 null */
    private static String parseCoded(String v, String def, String... allowed) {
        if (v == null || v.isBlank()) return def;
        String s = v.trim();
        int i = s.indexOf('-');
        if (i > 0) s = s.substring(0, i);
        for (String a : allowed) if (a.equals(s)) return s;
        return null;
    }

    /**
     * 导入（对齐生产「导入选项」）。表头须与模板逐列一致；前置校验任一条不过则整体不导入，
     * 错误按「信息校验日志」逐行返回。村(居)委会/开户银行 认「名称」或「编码-名称」两种写法；
     * 村组在本人辖区（数据范围）内解析并落 townId，同名村跨乡镇歧义时要求用「编码-名称」。
     */
    @PostMapping("/import")
    @Transactional(rollbackFor = Exception.class)
    public R<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
        List<Map<Integer, String>> raw = EasyExcel.read(file.getInputStream())
                .sheet().headRowNumber(0).doReadSync();
        if (raw.isEmpty()) throw new BizException("导入文件无有效数据行");
        Map<Integer, String> hdr = raw.get(0);
        for (int i = 0; i < HEADERS.size(); i++) {
            String h = hdr.get(i) == null ? "" : hdr.get(i).trim();
            if (!h.equals(HEADERS.get(i)))
                return R.ok(Map.of("count", 0, "errors", List.of(
                        "导入文件表头与模板不一致（第" + (i + 1) + "列应为「" + HEADERS.get(i)
                                + "」，实际为「" + h + "」），请先导出最新模板后按模板填报！")));
        }

        // 字典：辖区内村组（名称->列表、编码->村）/ 银行全称 / 辖区乡镇集
        Set<Long> allowed = dataScope.allowedTowns();   // null=全域(admin)
        QueryWrapper<YktVillage> vw = new QueryWrapper<>();
        if (allowed != null) {
            if (allowed.isEmpty()) throw new BizException("当前账号无可维护的乡镇范围");
            vw.in("TOWN_ID", allowed);
        }
        Map<String, List<YktVillage>> vByName = new HashMap<>();
        Map<String, YktVillage> vByCode = new HashMap<>();
        for (YktVillage v : villageMapper.selectList(vw)) {
            if (v.getVillageName() != null) vByName.computeIfAbsent(v.getVillageName().trim(), k -> new ArrayList<>()).add(v);
            if (v.getVillageCode() != null && !v.getVillageCode().isBlank()) vByCode.put(v.getVillageCode().trim(), v);
        }
        Map<String, Long> bankByName = new HashMap<>();
        for (YktBank b : bankMapper.selectList(null))
            if (b.getBankName() != null) bankByName.put(b.getBankName().trim(), b.getId());

        List<String> errs = new ArrayList<>();
        List<YktBeneficiary> out = new ArrayList<>();
        Set<String> seenIdCards = new HashSet<>();
        Map<String, String> dupMeta = new LinkedHashMap<>();   // idCard -> "Excel行号：N 家庭成员姓名：X"（库内查重报错用）
        for (int r = 1; r < raw.size(); r++) {
            Map<Integer, String> row = raw.get(r);
            boolean blank = true;
            for (int c = 0; c < HEADERS.size() && blank; c++)
                if (row.get(c) != null && !row.get(c).isBlank()) blank = false;
            if (blank) continue;
            int rowNo = r + 1;
            String[] v = new String[HEADERS.size()];
            for (int c = 0; c < HEADERS.size(); c++) v[c] = row.get(c) == null ? "" : row.get(c).trim();

            for (int c = 0; c < HEADERS.size(); c++)
                if (v[c].isEmpty() && REQUIRED.contains(HEADERS.get(c)))
                    errs.add("Excel行号：" + rowNo + "，填报的数据中存在为空的必填项（" + HEADERS.get(c) + "），请核对！");

            YktBeneficiary e = new YktBeneficiary();
            String status = parseCoded(v[0], "1", "0", "1", "2");
            if (status == null) { errs.add("Excel行号：" + rowNo + " 状态：" + v[0] + " 不合法（0-作废/1-正常/2-停用），请核对！"); }
            e.setStatus(status);
            e.setHouseholdCode(v[1]); e.setHeadName(v[2]); e.setHeadIdCard(v[3]);
            e.setName(v[4]); e.setIdCard(v[5]);
            for (int c : new int[]{3, 5})
                if (!v[c].isEmpty() && v[c].length() != 18)
                    errs.add("Excel行号：" + rowNo + " " + HEADERS.get(c) + "：" + v[c] + " ，位数(" + v[c].length() + ")错误，请核对！");

            // 村(居)委会：认「名称」或「编码-名称」；须在本人辖区内，同名跨乡镇须用编码消歧
            if (!v[6].isEmpty()) {
                String raw6 = v[6];
                int dash = raw6.indexOf('-');
                YktVillage hit = null;
                if (dash > 0 && raw6.substring(0, dash).matches("\\d+")) hit = vByCode.get(raw6.substring(0, dash));
                if (hit == null) {
                    List<YktVillage> l = vByName.get(stripCodePrefix(raw6));
                    if (l == null)
                        errs.add("Excel行号：" + rowNo + " 村(居)委会：" + raw6 + " 与本辖区村组维护不一致，请核对！");
                    else if (l.size() > 1)
                        errs.add("Excel行号：" + rowNo + " 村(居)委会：" + raw6 + " 在多个乡镇存在同名村，请填写「村编码-村名称」消歧！");
                    else hit = l.get(0);
                }
                if (hit != null) { e.setVillageId(hit.getId()); e.setTownId(hit.getTownId()); }
            }
            e.setGroupName(v[7]);
            String gender = parseCoded(v[8], null, "1", "2");
            if (v[8] != null && !v[8].isBlank() && gender == null) {
                if ("男".equals(v[8])) gender = "1"; else if ("女".equals(v[8])) gender = "2";
                else errs.add("Excel行号：" + rowNo + " 性别：" + v[8] + " 不合法（1-男/2-女），请核对！");
            }
            e.setGender(gender);
            e.setPhone(v[9]);
            if (!v[10].isEmpty()) {   // 年龄不是校验列：可空，解析不了也不报错（能解析才落库）
                String age = v[10].endsWith(".0") ? v[10].substring(0, v[10].length() - 2) : v[10];
                if (age.matches("\\d{1,3}")) e.setAge(Integer.parseInt(age));
            }
            String rel = v[11].replaceFirst("^\\d{1,2}-", "");   // 认「01-本人」或「本人」
            if (!rel.isEmpty() && !RELATIONS.contains(rel))
                errs.add("Excel行号：" + rowNo + " 与户主关系：" + v[11] + " 不在系统允许值内，请从模板下拉中选择！");
            e.setRelation(rel);
            e.setAccountName(v[12]); e.setBankAccount(v[13]);
            if (!v[14].isEmpty()) {
                Long bankId = bankByName.get(stripCodePrefix(v[14]));
                if (bankId == null) errs.add("Excel行号：" + rowNo + " 开户银行：" + v[14] + " 与系统银行设置不一致，请核对！");
                e.setBankId(bankId);
            }
            e.setPublicAccountName(v[15]); e.setPublicAccountNo(v[16]);
            if (!v[17].isEmpty()) {
                Long pb = bankByName.get(stripCodePrefix(v[17]));
                if (pb == null) errs.add("Excel行号：" + rowNo + " 公共账户开户行：" + v[17] + " 与系统银行设置不一致，请核对！");
                e.setPublicBankId(pb);
            }
            String[] flags = new String[4];
            String[] flagLabel = {"是否低保户", "是否乡村人口", "是否建档立卡户", "是否特困人员"};
            for (int k = 0; k < 4; k++) {
                String fv = v[18 + k].replace("乡村", "1").replace("是", "1").replace("否", "0");
                flags[k] = parseCoded(fv, "0", "0", "1");
                if (flags[k] == null) errs.add("Excel行号：" + rowNo + " " + flagLabel[k] + "：" + v[18 + k] + " 不合法（0-否/1-是），请核对！");
            }
            e.setIsDibao(flags[0]); e.setIsRural(flags[1]); e.setIsFiling(flags[2]); e.setIsTekun(flags[3]);
            e.setRemark(v[22]);   // 创建人列导入时忽略，由系统记录

            if (!v[5].isEmpty()) {
                if (!seenIdCards.add(v[5]))
                    errs.add("Excel行号：" + rowNo + " 身份证号：" + v[5] + " 在文件内重复，请核对！");
                else
                    dupMeta.put(v[5], "Excel行号：" + rowNo + " 家庭成员姓名：" + v[4]);
            }
            e.setReferred(0);
            out.add(e);
        }
        if (out.isEmpty()) throw new BizException("导入文件无有效数据行");

        // 库内查重（分块防 ORA-01795）：全库查（跨辖区也算重复），报错指明现存记录归属谁的辖区（对齐生产文案）
        List<String> ids = seenIdCards.stream().toList();
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < ids.size(); i += 900) {
            QueryWrapper<YktBeneficiary> dw = new QueryWrapper<>();
            dw.in("ID_CARD", ids.subList(i, Math.min(i + 900, ids.size())));
            for (YktBeneficiary d : mapper.selectList(dw)) {
                if (!reported.add(d.getIdCard())) continue;   // 同证多条（引用副本）只报一次
                String meta = dupMeta.getOrDefault(d.getIdCard(), "家庭成员姓名：" + d.getName());
                errs.add(meta + "，身份证号码：" + d.getIdCard()
                        + " 在系统中(" + ownerLabel(d.getTownId()) + ")已存在！");
            }
        }
        if (!errs.isEmpty()) return R.ok(Map.of("count", 0, "errors", errs));

        Long uid = UserContext.currentUserId();
        Long tid = UserContext.currentTenantId();
        for (YktBeneficiary e : out) {
            e.setTenantId(tid);
            e.setCreateBy(uid);
            e.setCreateTime(LocalDateTime.now());
            e.setUpdateTime(LocalDateTime.now());
            mapper.insert(e);
        }
        return R.ok(Map.of("count", out.size(), "errors", List.of()));
    }

    /** 一键导出本人管辖范围内的补贴对象花名册（.xls，参照生产导出：编码-名称/代码-标签口径） */
    @GetMapping("/export")
    public void export(HttpServletResponse resp) throws Exception {
        List<YktBeneficiary> list = mapper.selectList(buildQuery(new HashMap<>()));
        if (list.size() > 65000) throw new BizException("数据超过 65000 行，请联系管理员分范围导出");

        Map<Long, String> villageDisp = new HashMap<>();
        for (YktVillage v : villageMapper.selectList(null))
            villageDisp.put(v.getId(), (v.getVillageCode() == null || v.getVillageCode().isBlank())
                    ? v.getVillageName() : v.getVillageCode() + "-" + v.getVillageName());
        Map<Long, String> bankDisp = new HashMap<>();
        for (YktBank b : bankMapper.selectList(null)) {
            String no = (b.getUnionCode() != null && !b.getUnionCode().isBlank()) ? b.getUnionCode() : b.getBankCode();
            bankDisp.put(b.getId(), (no == null || no.isBlank()) ? b.getBankName() : no + "-" + b.getBankName());
        }
        Map<Long, String> userDisp = new HashMap<>();
        for (SysUser u : userMapper.selectList(null))
            userDisp.put(u.getId(), (u.getRealName() == null || u.getRealName().isBlank()) ? u.getUsername() : u.getRealName());

        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet sh = wb.createSheet("补贴对象花名册");
            Row head = sh.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                head.createCell(i).setCellValue(HEADERS.get(i));
                sh.setColumnWidth(i, 18 * 256);
            }
            Map<String, String> statusLabel = Map.of("0", "0-作废", "1", "1-正常", "2", "2-停用");
            Map<String, String> genderLabel = Map.of("1", "1-男", "2", "2-女");
            int r = 1;
            for (YktBeneficiary e : list) {
                Row row = sh.createRow(r++);
                int c = 0;
                for (String val : new String[]{
                        e.getStatus() == null ? "" : statusLabel.getOrDefault(e.getStatus(), e.getStatus()),
                        nz(e.getHouseholdCode()), nz(e.getHeadName()), nz(e.getHeadIdCard()),
                        nz(e.getName()), nz(e.getIdCard()),
                        e.getVillageId() == null ? "" : villageDisp.getOrDefault(e.getVillageId(), ""),
                        nz(e.getGroupName()),
                        e.getGender() == null ? "" : genderLabel.getOrDefault(e.getGender(), e.getGender()),
                        nz(e.getPhone()),
                        e.getAge() == null ? "" : String.valueOf(e.getAge()),
                        nz(e.getRelation()), nz(e.getAccountName()), nz(e.getBankAccount()),
                        e.getBankId() == null ? "" : bankDisp.getOrDefault(e.getBankId(), ""),
                        nz(e.getPublicAccountName()), nz(e.getPublicAccountNo()),
                        e.getPublicBankId() == null ? "" : bankDisp.getOrDefault(e.getPublicBankId(), ""),
                        yn(e.getIsDibao(), "1-是"), yn(e.getIsRural(), "1-乡村"),
                        yn(e.getIsFiling(), "1-是"), yn(e.getIsTekun(), "1-是"),
                        nz(e.getRemark()),
                        e.getCreateBy() == null ? "" : userDisp.getOrDefault(e.getCreateBy(), "")}) {
                    Cell cell = row.createCell(c++);
                    cell.setCellValue(val);
                }
            }
            resp.setContentType("application/vnd.ms-excel");
            resp.setCharacterEncoding("utf-8");
            String fn = java.net.URLEncoder.encode("补贴对象花名册", java.nio.charset.StandardCharsets.UTF_8);
            resp.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fn + ".xls");
            wb.write(resp.getOutputStream());
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String yn(String v, String yes) { return "1".equals(v) ? yes : (v == null || v.isBlank() ? "" : "0-否"); }
}
