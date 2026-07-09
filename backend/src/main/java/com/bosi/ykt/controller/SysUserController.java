package com.bosi.ykt.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.R;
import com.bosi.ykt.entity.SysOrg;
import com.bosi.ykt.entity.SysRole;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.entity.SysUserProject;
import com.bosi.ykt.entity.SysUserRole;
import com.bosi.ykt.mapper.SysOrgMapper;
import com.bosi.ykt.mapper.SysRoleMapper;
import com.bosi.ykt.mapper.SysUserMapper;
import com.bosi.ykt.mapper.SysUserProjectMapper;
import com.bosi.ykt.mapper.SysUserRoleMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/sys/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserMapper mapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserProjectMapper userProjectMapper;
    private final SysOrgMapper orgMapper;
    private final SysRoleMapper roleMapper;

    @GetMapping("/page")
    public R<IPage<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
                                  @RequestParam(defaultValue = "10") long pageSize,
                                  @RequestParam(required = false) String username,
                                  @RequestParam(required = false) String realName) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>()
                .like(username != null && !username.isBlank(), SysUser::getUsername, username)
                .like(realName != null && !realName.isBlank(), SysUser::getRealName, realName)
                .orderByDesc(SysUser::getId);
        IPage<SysUser> p = mapper.selectPage(new Page<>(pageNum, pageSize), w);
        p.getRecords().forEach(u -> u.setPassword(null));
        enrich(p.getRecords());
        return R.ok(p);
    }

    /** 列表富化：所属机构名 + 角色名（仅当前页范围，批量两查一关联，不逐行打库） */
    private void enrich(List<SysUser> users) {
        if (users.isEmpty()) return;
        Set<Long> orgIds = users.stream().map(SysUser::getOrgId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> orgName = orgIds.isEmpty() ? Map.of() :
                orgMapper.selectBatchIds(orgIds).stream()
                        .collect(Collectors.toMap(SysOrg::getId, SysOrg::getOrgName));
        List<Long> uids = users.stream().map(SysUser::getId).toList();
        List<SysUserRole> urs = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, uids));
        Set<Long> roleIds = urs.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        Map<Long, String> roleName = roleIds.isEmpty() ? Map.of() :
                roleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));
        Map<Long, List<Long>> rolesOf = urs.stream().collect(
                Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));
        for (SysUser u : users) {
            u.setOrgName(u.getOrgId() == null ? null : orgName.get(u.getOrgId()));
            u.setRoleNames(rolesOf.getOrDefault(u.getId(), List.of()).stream()
                    .map(rid -> roleName.getOrDefault(rid, "#" + rid))
                    .collect(Collectors.joining("、")));
        }
    }

    @GetMapping("/{id}")
    public R<SysUser> detail(@PathVariable Long id) {
        SysUser u = mapper.selectById(id);
        if (u != null) {
            u.setPassword(null);
            u.setRoleIds(userRoleMapper.selectList(
                            new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id))
                    .stream().map(SysUserRole::getRoleId).toList());
        }
        return R.ok(u);
    }

    @PostMapping
    public R<?> create(@RequestBody SysUser u) {
        u.setPassword(BCrypt.hashpw(u.getPassword() == null || u.getPassword().isBlank() ? "123456" : u.getPassword(), BCrypt.gensalt()));
        if (u.getStatus() == null) u.setStatus(1);
        mapper.insert(u);
        saveRoles(u.getId(), u.getRoleIds());
        return R.ok(u);
    }

    @PutMapping
    public R<?> update(@RequestBody SysUser u) {
        u.setPassword(null); // 密码改不在此
        mapper.updateById(u);
        saveRoles(u.getId(), u.getRoleIds());
        return R.ok();
    }

    @PostMapping("/{id}/reset-password")
    public R<?> resetPwd(@PathVariable Long id) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setPassword(BCrypt.hashpw("123456", BCrypt.gensalt()));
        mapper.updateById(u);
        return R.ok();
    }

    @GetMapping("/{id}/roles")
    public R<List<Long>> roles(@PathVariable Long id) {
        return R.ok(userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id))
                .stream().map(SysUserRole::getRoleId).toList());
    }

    @PostMapping("/{id}/roles")
    public R<?> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        saveRoles(id, roleIds);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<?> remove(@PathVariable Long id) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        // 连带清项目授权，防 SYS_USER_PROJECT 残留脏行
        userProjectMapper.delete(new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, id));
        mapper.deleteById(id);
        return R.ok();
    }

    /** 分配数据·读取：返回该用户的机构(区域) + 已授权项目 id 列表 */
    @GetMapping("/{id}/data-scope")
    public R<Map<String, Object>> getDataScope(@PathVariable Long id) {
        SysUser u = mapper.selectById(id);
        Map<String, Object> m = new HashMap<>();
        m.put("orgId", u == null ? null : u.getOrgId());
        m.put("projectIds", userProjectMapper.selectList(
                        new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, id))
                .stream().map(SysUserProject::getProjectId).toList());
        return R.ok(m);
    }

    /** 分配数据·保存：写回机构(区域=orgId，县/乡镇由其 orgCode 前6位推出) + 覆盖授权项目 */
    @PostMapping("/{id}/data-scope")
    public R<?> saveDataScope(@PathVariable Long id, @RequestBody DataScopeReq req) {
        // 弹窗回显后整体提交，orgId 为准数据：null=显式清空（updateById 会忽略 null，须走 UpdateWrapper）
        mapper.update(null, new UpdateWrapper<SysUser>().eq("ID", id).set("ORG_ID", req.getOrgId()));
        userProjectMapper.delete(new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, id));
        if (req.getProjectIds() != null) {
            for (Long pid : req.getProjectIds()) {
                if (pid == null) continue;
                SysUserProject sp = new SysUserProject();
                sp.setUserId(id);
                sp.setProjectId(pid);
                userProjectMapper.insert(sp);
            }
        }
        return R.ok();
    }

    @Data
    public static class DataScopeReq {
        private Long orgId;
        private List<Long> projectIds;
    }

    /** null=不动现有角色（防不带 roleIds 的 PUT 静默清空绑定）；空数组=显式清空。 */
    private void saveRoles(Long userId, List<Long> roleIds) {
        if (userId == null || roleIds == null) return;
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (Long rid : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(rid);
            userRoleMapper.insert(ur);
        }
    }
}
