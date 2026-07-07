package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktAgency;
import com.bosi.ykt.entity.YktVillage;
import com.bosi.ykt.mapper.YktAgencyMapper;
import com.bosi.ykt.mapper.YktVillageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 机构/部门字典查询。供各下拉（主管部门/单位/乡镇）选择。 */
@RestController
@RequestMapping("/agency")
@RequiredArgsConstructor
public class YktAgencyController {

    private final YktAgencyMapper mapper;
    private final YktVillageMapper villageMapper;

    /** 列表（可按名称/编码模糊；onlySubsidy=1 只取补贴单位如乡镇政府；county 按县市过滤，不传=全部供 admin） */
    @GetMapping("/list")
    public R<List<YktAgency>> list(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String onlySubsidy,
                                  @RequestParam(required = false) Integer level,
                                  @RequestParam(required = false) String county) {
        LambdaQueryWrapper<YktAgency> w = new LambdaQueryWrapper<YktAgency>()
                .eq(YktAgency::getStatus, 1);
        if ("1".equals(onlySubsidy)) w.eq(YktAgency::getIsSubsidy, "1");
        if (level != null) w.eq(YktAgency::getLevelNo, level);
        if (county != null && !county.isBlank()) w.eq(YktAgency::getCountyCode, county.trim());
        if (keyword != null && !keyword.isBlank())
            w.and(x -> x.like(YktAgency::getName, keyword.trim()).or().like(YktAgency::getCode, keyword.trim()));
        w.orderByAsc(YktAgency::getOrderNum).orderByAsc(YktAgency::getCode);
        return R.ok(mapper.selectList(w));
    }

    /**
     * 村组级联：仅乡镇政府（机构 isSubsidy=1，已链接 TOWN_ID）才有村组。
     * 入参 townId = SYS_ORG 乡镇 id（取自机构记录的 townId 字段）。返回 [{code,name}]。
     * 部门/股室/学校等无 townId，前端不会调到这里。
     */
    @GetMapping("/villages")
    public R<List<Map<String, Object>>> villages(@RequestParam(required = false) Long townId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (townId == null) return R.ok(out);
        List<YktVillage> vs = villageMapper.selectList(new LambdaQueryWrapper<YktVillage>()
                .eq(YktVillage::getTownId, townId)
                .orderByAsc(YktVillage::getVillageCode));
        for (YktVillage v : vs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", v.getVillageCode());
            m.put("name", v.getVillageName());
            out.add(m);
        }
        return R.ok(out);
    }

    /** 树（按 superGuid 组装；前端可用 el-tree-select / el-cascader） */
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<YktAgency> all = mapper.selectList(new LambdaQueryWrapper<YktAgency>().eq(YktAgency::getStatus, 1)
                .orderByAsc(YktAgency::getOrderNum).orderByAsc(YktAgency::getCode));
        Map<String, Map<String, Object>> node = new LinkedHashMap<>();
        for (YktAgency a : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("guid", a.getGuid()); m.put("code", a.getCode()); m.put("name", a.getName());
            m.put("label", a.getCode() + "-" + a.getName()); m.put("superGuid", a.getSuperGuid());
            m.put("children", new ArrayList<>());
            node.put(a.getGuid(), m);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (YktAgency a : all) {
            Map<String, Object> m = node.get(a.getGuid());
            Map<String, Object> p = node.get(a.getSuperGuid());
            if (p != null) ((List<Map<String, Object>>) p.get("children")).add(m);
            else roots.add(m);
        }
        return R.ok(roots);
    }
}
