package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.YktBeneficiary;
import com.bosi.ykt.entity.YktReferRequest;
import com.bosi.ykt.entity.YktVillage;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.YktBeneficiaryMapper;
import com.bosi.ykt.mapper.YktReferRequestMapper;
import com.bosi.ykt.mapper.YktVillageMapper;
import com.bosi.ykt.security.DataScopeResolver;
import com.bosi.ykt.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引用请求审批流。乡镇 A 引用乡镇 B 名下补贴对象：
 * 1) A 填好落地村组 + 留言(必填) → 提交 PENDING 请求；
 * 2) B（被引用乡镇）在工作台看到待审引用，核对人员 + 留言；
 * 3) B 确认 → 按载荷复制建档(referred=1)，请求置 APPROVED；B 驳回 → REJECTED。
 */
@RestController
@RequestMapping("/setup/refer-request")
@RequiredArgsConstructor
public class YktReferRequestController {

    private final YktReferRequestMapper mapper;
    private final YktBeneficiaryMapper beneficiaryMapper;
    private final YktVillageMapper villageMapper;
    private final SysOrgMapper orgMapper;
    private final SysUserMapper userMapper;
    private final DataScopeResolver dataScope;
    private final ObjectMapper objectMapper;   // 注入的是配好 Long→String 的实例，雪花 id JSON 往返不丢精度

    public static class ReferReq {
        public YktBeneficiary payload;   // 引用方填好的整条补贴对象（含本乡镇 villageId/groupName）
        public String message;           // 留言（必填）
        public Long sourceId;            // 原始建档 id（前端从 refer 结果带回）
    }

    // ==================== 提交引用请求 ====================
    @PostMapping
    @Transactional
    public R<?> create(@RequestBody ReferReq req) {
        if (req == null || req.payload == null) throw new BizException("缺少引用信息");
        if (req.message == null || req.message.trim().isEmpty()) throw new BizException("请填写留言（必填）");

        // payload 只取「落地信息」（villageId/groupName）；身份、银行账号等一律以服务端 src 为准，绝不信客户端。
        YktBeneficiary p = req.payload;
        if (p.getVillageId() == null) throw new BizException("请选择落地村(居)委会");
        YktVillage v = villageMapper.selectById(p.getVillageId());
        if (v == null) throw new BizException("村(居)委会不存在");
        Long targetTownId = v.getTownId();
        assertInScope(targetTownId);   // 引用方只能把人落到本人辖区

        // 定位原始建档（信任源）：优先 sourceId，回退按身份证。身份/银行字段全部取自它。
        YktBeneficiary src = null;
        if (req.sourceId != null) src = beneficiaryMapper.selectById(req.sourceId);
        if (src == null && p.getIdCard() != null && !p.getIdCard().isBlank()) {
            List<YktBeneficiary> list = beneficiaryMapper.selectList(new QueryWrapper<YktBeneficiary>()
                    .eq("ID_CARD", p.getIdCard().trim()).orderByAsc("REFERRED"));
            if (!list.isEmpty()) src = list.get(0);
        }
        if (src == null) throw new BizException("未找到该身份证的原始补贴对象");
        String idCard = src.getIdCard();   // 身份证以 src 为准（客户端传的不作数）
        if (idCard == null || idCard.isBlank()) throw new BizException("原始建档缺少身份证号，无法引用");
        Long sourceTownId = src.getTownId();
        if (sourceTownId != null && sourceTownId.equals(targetTownId))
            throw new BizException("该对象已在本乡镇，无需引用");

        // 状态法去重①：目标乡镇当前是否已有该证的引用建档（referred=1）——已删副本可重引，不看历史看现状
        Long existCopy = beneficiaryMapper.selectCount(new QueryWrapper<YktBeneficiary>()
                .eq("ID_CARD", idCard.trim()).eq("TOWN_ID", targetTownId).eq("REFERRED", 1));
        if (existCopy != null && existCopy > 0) throw new BizException("该对象已引用建档在本乡镇，无需重复引用");

        // 去重②：同证 + 目标乡镇的在途请求（待审核 / 已通过未纳入）避免重复提交
        Long dup = mapper.selectCount(new QueryWrapper<YktReferRequest>()
                .eq("ID_CARD", idCard.trim())
                .eq("TARGET_TOWN_ID", targetTownId).in("STATUS", java.util.List.of("PENDING", "APPROVED")));
        if (dup != null && dup > 0) throw new BizException("已提交过该对象的引用请求（待审核或待纳入），勿重复提交");

        YktReferRequest r = new YktReferRequest();
        r.setIdCard(idCard);                    // ↓ 身份四件套全部来自 src
        r.setName(src.getName());
        r.setHeadName(src.getHeadName());
        r.setHeadIdCard(src.getHeadIdCard());
        r.setSourceId(src.getId());
        r.setSourceTownId(sourceTownId);
        r.setTargetTownId(targetTownId);
        r.setTargetVillageId(p.getVillageId());
        r.setTargetGroupName(p.getGroupName());
        r.setMessage(req.message.trim());
        r.setStatus("PENDING");
        r.setApplyUserId(UserContext.currentUserId());
        r.setApplyUserName(realName(UserContext.currentUserId()));
        r.setApplyTownLabel(ownerLabel(targetTownId));
        r.setSourceTownLabel(ownerLabel(sourceTownId));
        r.setApplyTime(LocalDateTime.now());
        try {
            // 载荷快照以 src 为底，仅覆盖落地信息（乡镇/村/组）——纳入时按此建档，银行账号等敏感字段来自 src
            YktBeneficiary snap = objectMapper.readValue(objectMapper.writeValueAsString(src), YktBeneficiary.class);
            snap.setId(null);
            snap.setReferred(1);
            snap.setTownId(targetTownId);
            snap.setVillageId(p.getVillageId());
            snap.setGroupName(p.getGroupName());
            snap.setTenantId(null); snap.setCreateBy(null); snap.setCreateTime(null);
            snap.setUpdateBy(null); snap.setUpdateTime(null); snap.setDeleted(null);
            snap.setCreateByName(null);
            r.setPayloadJson(objectMapper.writeValueAsString(snap));
        } catch (Exception e) { throw new BizException("引用载荷序列化失败：" + e.getMessage()); }
        mapper.insert(r);
        return R.ok(Map.of("sourceTownLabel", nz(r.getSourceTownLabel())));
    }

    // ==================== 待审引用（被引用乡镇 / 工作台）====================
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        return R.ok(listByStatus("PENDING"));
    }

    /** 被引用乡镇待审计数（工作台角标）。 */
    @GetMapping("/pending-count")
    public R<Integer> pendingCount() {
        return R.ok(listByStatus("PENDING").size());
    }

    // ==================== 我发起的引用（引用方看状态 + 待纳入通知）====================
    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        Set<Long> towns = dataScope.allowedTowns();   // null=管理员看全部
        QueryWrapper<YktReferRequest> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        if (towns != null) {
            if (towns.isEmpty()) return R.ok(new ArrayList<>());
            w.in("TARGET_TOWN_ID", towns);
        }
        w.orderByDesc("APPLY_TIME");
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktReferRequest r : mapper.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("idCard", r.getIdCard());
            m.put("headName", r.getHeadName());
            m.put("sourceTownLabel", r.getSourceTownLabel());
            m.put("targetVillageName", villageName(r.getTargetVillageId()));
            m.put("targetGroupName", r.getTargetGroupName());
            m.put("message", r.getMessage());
            m.put("status", r.getStatus());
            m.put("statusLabel", statusLabel(r.getStatus()));
            m.put("auditRemark", r.getAuditRemark());
            m.put("auditTime", r.getAuditTime());
            m.put("applyTime", r.getApplyTime());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 引用方「已通过待纳入」计数（工作台通知角标）。 */
    @GetMapping("/approved-count")
    public R<Integer> approvedCount() {
        Set<Long> towns = dataScope.allowedTowns();
        QueryWrapper<YktReferRequest> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.eq("STATUS", "APPROVED");
        if (towns != null) {
            if (towns.isEmpty()) return R.ok(0);
            w.in("TARGET_TOWN_ID", towns);
        }
        Long n = mapper.selectCount(w);
        return R.ok(n == null ? 0 : n.intValue());
    }

    private static String statusLabel(String s) {
        if ("PENDING".equals(s)) return "待审核";
        if ("APPROVED".equals(s)) return "已通过·待纳入";
        if ("REJECTED".equals(s)) return "已拒绝";
        if ("INCLUDED".equals(s)) return "已纳入";
        return s;
    }

    private List<Map<String, Object>> listByStatus(String status) {
        Set<Long> towns = dataScope.allowedTowns();   // null=管理员看全部
        QueryWrapper<YktReferRequest> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.eq("STATUS", status);
        if (towns != null) {
            if (towns.isEmpty()) return new ArrayList<>();
            w.in("SOURCE_TOWN_ID", towns);
        }
        w.orderByDesc("APPLY_TIME");
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktReferRequest r : mapper.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("idCard", r.getIdCard());
            m.put("headName", r.getHeadName());
            m.put("headIdCard", r.getHeadIdCard());
            m.put("message", r.getMessage());
            m.put("applyUserName", r.getApplyUserName());
            m.put("applyTownLabel", r.getApplyTownLabel());
            m.put("sourceTownLabel", r.getSourceTownLabel());
            m.put("applyTime", r.getApplyTime());
            m.put("targetVillageName", villageName(r.getTargetVillageId()));
            m.put("targetGroupName", r.getTargetGroupName());
            out.add(m);
        }
        return out;
    }

    // ==================== 确认引用（被引用乡镇：只置 APPROVED，不建档）====================
    @PostMapping("/{id}/approve")
    @Transactional
    public R<?> approve(@PathVariable Long id) {
        mustPending(id);   // 越权兜底（仅被引用乡镇/管理员）+ 存在性 + 当前须 PENDING
        // 审核通过 ≠ 直接入库：只标 APPROVED，等引用方点「纳入补贴对象库」才复制建档，形成闭环。
        // 条件更新 WHERE STATUS='PENDING' 守门，并发重复审批只有一个生效。
        int ok = mapper.update(null, new UpdateWrapper<YktReferRequest>()
                .set("STATUS", "APPROVED")
                .set("AUDIT_USER_ID", UserContext.currentUserId())
                .set("AUDIT_TIME", LocalDateTime.now())
                .eq("ID", id).eq("STATUS", "PENDING"));
        if (ok == 0) throw new BizException("该引用请求已处理");
        return R.ok();
    }

    // ==================== 纳入补贴对象库（引用方：APPROVED → 建档 → INCLUDED）====================
    @PostMapping("/{id}/include")
    @Transactional
    public R<?> include(@PathVariable Long id) {
        YktReferRequest r = id == null ? null : mapper.selectById(id);
        if (r == null) throw new BizException("引用请求不存在");
        if (!"APPROVED".equals(r.getStatus())) throw new BizException("该请求未通过审核或已纳入");
        assertInScope(r.getTargetTownId());   // 只有引用方乡镇能纳入
        // 原子抢占 APPROVED→INCLUDING：并发双击/重试只有一个抢到，防同一人建两份档
        int locked = mapper.update(null, new UpdateWrapper<YktReferRequest>()
                .set("STATUS", "INCLUDING").eq("ID", id).eq("STATUS", "APPROVED"));
        if (locked == 0) throw new BizException("该请求正在纳入或已纳入，请勿重复操作");
        Long copyId = createCopy(r);
        mapper.update(null, new UpdateWrapper<YktReferRequest>()
                .set("STATUS", "INCLUDED").set("RESULT_BENEFICIARY_ID", copyId)
                .eq("ID", id));
        return R.ok();
    }

    /** 按 payloadJson 复制建档（referred=1，落引用方乡镇）。 */
    private Long createCopy(YktReferRequest r) {
        YktBeneficiary copy;
        try { copy = objectMapper.readValue(r.getPayloadJson(), YktBeneficiary.class); }
        catch (Exception e) { throw new BizException("引用载荷解析失败：" + e.getMessage()); }
        copy.setId(null);
        copy.setReferred(1);
        copy.setTownId(r.getTargetTownId());
        copy.setVillageId(r.getTargetVillageId());
        // 清空审计字段，交给 MetaHandler 重新填（避免带入申请方序列化时的残值）
        copy.setTenantId(null); copy.setCreateBy(null); copy.setCreateTime(null);
        copy.setUpdateBy(null); copy.setUpdateTime(null); copy.setDeleted(null);
        beneficiaryMapper.insert(copy);
        return copy.getId();
    }

    // ==================== 拒绝引用 ====================
    @PostMapping("/{id}/reject")
    @Transactional
    public R<?> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        mustPending(id);   // 越权兜底 + 当前须 PENDING
        int ok = mapper.update(null, new UpdateWrapper<YktReferRequest>()
                .set("STATUS", "REJECTED")
                .set("AUDIT_USER_ID", UserContext.currentUserId())
                .set("AUDIT_TIME", LocalDateTime.now())
                .set("AUDIT_REMARK", body == null ? null : java.util.Objects.toString(body.get("remark"), null))
                .eq("ID", id).eq("STATUS", "PENDING"));
        if (ok == 0) throw new BizException("该引用请求已处理");
        return R.ok();
    }

    // ==================== helpers ====================
    /** 取 PENDING 请求 + 审批方越权兜底（仅被引用乡镇 / 管理员可操作）。 */
    private YktReferRequest mustPending(Long id) {
        YktReferRequest r = id == null ? null : mapper.selectById(id);
        if (r == null) throw new BizException("引用请求不存在");
        if (!"PENDING".equals(r.getStatus())) throw new BizException("该引用请求已处理");
        assertInScope(r.getSourceTownId());   // 只有被引用乡镇（拥有原始建档）能审批
        return r;
    }

    /** 目标乡镇须在本人辖区：委托 DataScopeResolver 单一真源。 */
    private void assertInScope(Long townId) {
        dataScope.assertTown(townId, "该引用请求");
    }

    private String realName(Long uid) {
        if (uid == null) return null;
        SysUser u = userMapper.selectById(uid);
        return u == null ? null : u.getRealName();
    }

    private String villageName(Long villageId) {
        if (villageId == null) return null;
        YktVillage v = villageMapper.selectById(villageId);
        return v == null ? null : v.getVillageName();
    }

    /** 归属辖区展示（对齐生产文案）：如「甲县-甲县一号镇」。 */
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

    private static String nz(String s) { return s == null ? "" : s; }
}
