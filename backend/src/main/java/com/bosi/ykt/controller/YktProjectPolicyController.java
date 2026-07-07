package com.bosi.ykt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.YktPolicy;
import com.bosi.ykt.entity.YktProject;
import com.bosi.ykt.entity.YktProjectPolicy;
import com.bosi.ykt.mapper.YktPolicyMapper;
import com.bosi.ykt.mapper.YktProjectMapper;
import com.bosi.ykt.mapper.YktProjectPolicyMapper;
import com.bosi.ykt.security.UserContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 政策关联项目（主管部门）。项目 ↔ 政策 多对多。
 *  - 左侧项目树：已纳入项目（/projects）
 *  - 选中项目 -> 右表展示已关联政策（/linked）
 *  - 「关联」弹出全部政策分页（/candidates）勾选 -> /link 追加（去重）
 *  - 右表勾选 -> /unlink 物理删除关联
 */
@RestController
@RequestMapping("/dept/project-policy")
@RequiredArgsConstructor
public class YktProjectPolicyController {

    private final YktProjectPolicyMapper linkMapper;
    private final YktProjectMapper projectMapper;
    private final YktPolicyMapper policyMapper;

    /** 项目树数据源：已纳入项目（id/编码/名称/主管部门） */
    @GetMapping("/projects")
    public R<List<YktProject>> projects(@RequestParam(required = false) String projectName) {
        QueryWrapper<YktProject> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        w.eq("INCLUDED", 1);
        if (projectName != null && !projectName.isBlank()) w.like("PROJECT_NAME", projectName.trim());
        w.orderByAsc("PROJECT_CODE").orderByDesc("ID");
        return R.ok(projectMapper.selectList(w));
    }

    /** 某项目已关联的政策 */
    @GetMapping("/linked")
    public R<List<YktPolicy>> linked(@RequestParam Long projectId) {
        if (projectId == null) throw new BizException("缺少项目");
        List<Long> policyIds = linkedPolicyIds(projectId);
        if (policyIds.isEmpty()) return R.ok(List.of());
        // selectBatchIds 自动按 @TableLogic 过滤已删政策；保持按 ID 倒序
        QueryWrapper<YktPolicy> w = new QueryWrapper<>();
        w.in("ID", policyIds).orderByDesc("ID");
        return R.ok(policyMapper.selectList(w));
    }

    /** 候选政策（关联弹窗）：全部政策分页，按 政策文号 / 政策标题 模糊筛选 */
    @GetMapping("/candidates")
    public R<IPage<YktPolicy>> candidates(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "999") long pageSize,
                                          @RequestParam(required = false) String policyNo,
                                          @RequestParam(required = false) String title) {
        QueryWrapper<YktPolicy> w = new QueryWrapper<>();
        Long tid = UserContext.currentTenantId();
        if (tid != null) w.eq("TENANT_ID", tid);
        if (policyNo != null && !policyNo.isBlank()) w.like("POLICY_NO", policyNo.trim());
        if (title != null && !title.isBlank()) w.like("TITLE", title.trim());
        w.orderByDesc("ID");
        return R.ok(policyMapper.selectPage(new Page<>(pageNum, pageSize), w));
    }

    @Data
    public static class LinkReq {
        private Long projectId;
        private List<Long> policyIds;
    }

    /** 关联（追加，去重） */
    @PostMapping("/link")
    @Transactional(rollbackFor = Exception.class)
    public R<?> link(@RequestBody LinkReq req) {
        if (req == null || req.getProjectId() == null) throw new BizException("缺少项目");
        if (req.getPolicyIds() == null || req.getPolicyIds().isEmpty()) throw new BizException("请勾选要关联的政策");
        List<Long> existed = linkedPolicyIds(req.getProjectId());
        Long tid = UserContext.currentTenantId();
        Long uid = UserContext.currentUserId();
        int added = 0;
        for (Long pid : req.getPolicyIds()) {
            if (pid == null || existed.contains(pid)) continue;   // 已关联跳过
            YktProjectPolicy link = new YktProjectPolicy();
            link.setProjectId(req.getProjectId());
            link.setPolicyId(pid);
            link.setTenantId(tid);
            link.setCreateBy(uid);
            link.setCreateTime(LocalDateTime.now());
            linkMapper.insert(link);
            added++;
        }
        return R.ok(Map.of("added", added));
    }

    /** 取消关联（物理删除） */
    @PostMapping("/unlink")
    @Transactional(rollbackFor = Exception.class)
    public R<?> unlink(@RequestBody LinkReq req) {
        if (req == null || req.getProjectId() == null) throw new BizException("缺少项目");
        if (req.getPolicyIds() == null || req.getPolicyIds().isEmpty()) throw new BizException("请勾选要取消关联的政策");
        QueryWrapper<YktProjectPolicy> w = new QueryWrapper<>();
        w.eq("PROJECT_ID", req.getProjectId()).in("POLICY_ID", req.getPolicyIds());
        int n = linkMapper.delete(w);
        return R.ok(Map.of("removed", n));
    }

    private List<Long> linkedPolicyIds(Long projectId) {
        return linkMapper.selectList(new QueryWrapper<YktProjectPolicy>().eq("PROJECT_ID", projectId))
                .stream().map(YktProjectPolicy::getPolicyId).toList();
    }
}
