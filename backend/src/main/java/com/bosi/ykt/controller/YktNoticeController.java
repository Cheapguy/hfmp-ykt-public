package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.YktAgency;
import com.bosi.ykt.entity.YktNotice;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.YktAgencyMapper;
import com.bosi.ykt.mapper.YktNoticeMapper;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知公告管理（主管部门端上传/下发 + 乡镇端下载）。
 * <p>下发口径：{@code targetOrgIds} 逗号分隔存乡镇 SYS_ORG.id（= YKT_AGENCY.townId）。
 * 乡镇端仅可见 targetOrgIds 含本机构 id 的公告；未下发（空）= 无人可见。
 */
@RestController
@RequestMapping("/dept/notice")
@RequiredArgsConstructor
public class YktNoticeController extends BaseCrudController<YktNoticeMapper, YktNotice> {

    private final YktNoticeMapper mapper;
    private final YktAgencyMapper agencyMapper;
    private final SysUserMapper userMapper;
    private final SysOrgMapper orgMapper;

    @Value("${ykt.upload.base-dir}")
    private String baseDir;

    @Override protected YktNoticeMapper getMapper() { return mapper; }

    /** 主管部门端列表：租户隔离 + 标题/文件名模糊 + 时间倒序 */
    @Override
    protected QueryWrapper<YktNotice> buildQuery(Map<String, Object> params) {
        QueryWrapper<YktNotice> w = super.buildQuery(params);
        Object title = params.get("title");
        if (title != null && !title.toString().isBlank()) w.like("TITLE", title.toString().trim());
        Object fn = params.get("fileName");
        if (fn != null && !fn.toString().isBlank()) w.like("FILE_NAME", fn.toString().trim());
        w.orderByDesc("CREATE_TIME");
        return w;
    }

    /** 上传通知：文件落 base-dir，登记一条公告（title/fileName=原始文件名，fileUrl 指向 /files/preview） */
    @PostMapping("/upload")
    public R<YktNotice> upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) String title) throws Exception {
        if (file == null || file.isEmpty()) throw new BizException("请选择要上传的文件");
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) original = "notice";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) ext = original.substring(dot);
        String stored = UUID.randomUUID().toString().replace("-", "") + ext;

        Path dir = Paths.get(baseDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(stored).normalize();
        file.transferTo(target.toFile());

        YktNotice n = new YktNotice();
        n.setTitle(title != null && !title.isBlank() ? title.trim() : original);
        n.setFileName(original);
        n.setFileUrl("/hfmp-ykt/api/files/preview/" + stored
                + "?fn=" + URLEncoder.encode(original, StandardCharsets.UTF_8));
        n.setStatus(1);
        mapper.insert(n);   // MetaObjectHandler 自动填 tenantId/createTime/createBy 等
        return R.ok(n);
    }

    /** 下发单位候选：按当前用户所属县（org.orgCode 前 6 位）取乡镇政府（补贴单位） */
    @GetMapping("/towns")
    public R<List<Map<String, Object>>> towns() {
        String county = currentCounty();
        LambdaQueryWrapper<YktAgency> w = new LambdaQueryWrapper<YktAgency>()
                .eq(YktAgency::getIsSubsidy, "1").eq(YktAgency::getStatus, 1);
        if (county != null) w.eq(YktAgency::getCountyCode, county);
        w.orderByAsc(YktAgency::getOrderNum).orderByAsc(YktAgency::getCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (YktAgency a : agencyMapper.selectList(w)) {
            if (a.getTownId() == null) continue;   // 无行政区划乡镇 id 的无法下发/匹配
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getTownId());
            m.put("name", a.getName());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 下发：把选中乡镇写入公告 targetOrgIds（逗号分隔）。空数组=收回下发（无人可见） */
    @PostMapping("/dispatch")
    public R<?> dispatch(@RequestBody DispatchReq req) {
        if (req.getNoticeId() == null) throw new BizException("缺少公告");
        YktNotice n = mapper.selectById(req.getNoticeId());
        if (n == null) throw new BizException("公告不存在");
        String ids = (req.getOrgIds() == null) ? "" :
                req.getOrgIds().stream().filter(Objects::nonNull).map(String::valueOf)
                        .collect(Collectors.joining(","));
        n.setTargetOrgIds(ids);          // 非 null，updateById 正常写入（含清空为 ""）
        mapper.updateById(n);
        return R.ok(Map.of("count", ids.isEmpty() ? 0 : ids.split(",").length));
    }

    /** 乡镇端「通知下载」：仅返回下发给本机构的公告 */
    @GetMapping("/mine")
    public R<List<YktNotice>> mine() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        Long orgId = u == null ? null : u.getOrgId();
        if (orgId == null) return R.ok(List.of());
        String oid = String.valueOf(orgId);
        Long tid = UserContext.currentTenantId();

        LambdaQueryWrapper<YktNotice> w = new LambdaQueryWrapper<>();
        if (tid != null) w.eq(YktNotice::getTenantId, tid);
        // 匹配逗号分隔中的整段 id，避免 62292 误命中 990008
        w.and(x -> x.eq(YktNotice::getTargetOrgIds, oid)
                .or().likeRight(YktNotice::getTargetOrgIds, oid + ",")
                .or().likeLeft(YktNotice::getTargetOrgIds, "," + oid)
                .or().like(YktNotice::getTargetOrgIds, "," + oid + ","));
        w.orderByDesc(YktNotice::getCreateTime);
        return R.ok(mapper.selectList(w));
    }

    /** 当前用户所属县码：org.orgCode 前 6 位（如 990003010000 → 990003），取不到返回 null=全部县 */
    private String currentCounty() {
        Long uid = UserContext.currentUserId();
        SysUser u = uid == null ? null : userMapper.selectById(uid);
        if (u == null || u.getOrgId() == null) return null;
        SysOrg org = orgMapper.selectById(u.getOrgId());
        if (org == null || org.getOrgCode() == null || org.getOrgCode().length() < 6) return null;
        return org.getOrgCode().substring(0, 6);
    }

    @Data
    public static class DispatchReq {
        private Long noticeId;
        private List<Long> orgIds;
    }
}
