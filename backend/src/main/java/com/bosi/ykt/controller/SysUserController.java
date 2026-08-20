package com.bosi.ykt.controller;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bosi.ykt.common.BizException;
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
import com.bosi.ykt.security.UserContext;
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
    private final com.bosi.ykt.security.DataScopeResolver dataScope;

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
        assertCanGrantUserType(u.getUserType());
        assertAssignableRoles(u.getRoleIds());
        assertOrgAssignable(u.getOrgId());   // 与 data-scope 同一道：ORG_ID 决定县域，走哪个入口都要过
        assertUsernameFree(u.getUsername(), null);
        u.setUsername(u.getUsername().trim());
        String raw = u.getPassword() == null || u.getPassword().isBlank() ? randomPassword() : u.getPassword();
        if (raw.length() < 6) throw new BizException("密码至少6位");
        u.setPassword(BCrypt.hashpw(raw, BCrypt.gensalt()));
        if (u.getStatus() == null) u.setStatus(1);
        mapper.insert(u);
        saveRoles(u.getId(), u.getRoleIds());
        // 不回吐 hash：口令散列是离线爆破的原料，管理页面不需要它
        Map<String, Object> m = new HashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("initPassword", raw);   // 仅本次响应可见，管理员须转交本人
        return R.ok(m);
    }

    @PutMapping
    public R<?> update(@RequestBody SysUser u) {
        assertCanManage(u.getId());
        assertCanGrantUserType(u.getUserType());
        assertAssignableRoles(u.getRoleIds());
        assertOrgAssignable(u.getOrgId());   // 同上：把「分配数据」堵了、用户保存没堵等于没堵
        if (u.getUsername() != null && !u.getUsername().isBlank()) {
            assertUsernameFree(u.getUsername(), u.getId());
            u.setUsername(u.getUsername().trim());
        }
        u.setPassword(null); // 密码改不在此
        mapper.updateById(u);
        saveRoles(u.getId(), u.getRoleIds());
        return R.ok();
    }

    /** 重置为一次性随机口令并原样返回；不再固定 123456（可枚举、且没人会去改）。 */
    @PostMapping("/{id}/reset-password")
    public R<Map<String, Object>> resetPwd(@PathVariable Long id) {
        assertCanManage(id);
        String raw = randomPassword();
        SysUser u = new SysUser();
        u.setId(id);
        u.setPassword(BCrypt.hashpw(raw, BCrypt.gensalt()));
        mapper.updateById(u);
        Map<String, Object> m = new HashMap<>();
        m.put("password", raw);
        return R.ok(m);
    }

    @GetMapping("/{id}/roles")
    public R<List<Long>> roles(@PathVariable Long id) {
        return R.ok(userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id))
                .stream().map(SysUserRole::getRoleId).toList());
    }

    @PostMapping("/{id}/roles")
    public R<?> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        assertCanManage(id);
        assertAssignableRoles(roleIds);
        saveRoles(id, roleIds);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<?> remove(@PathVariable Long id) {
        assertCanManage(id);
        if (id != null && id.equals(UserContext.currentUserId())) throw new BizException("不能删除当前登录账号");
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        // 连带清项目授权，防 SYS_USER_PROJECT 残留脏行
        userProjectMapper.delete(new LambdaQueryWrapper<SysUserProject>().eq(SysUserProject::getUserId, id));
        mapper.deleteById(id);
        return R.ok();
    }

    /** 分配数据·读取：返回该用户的机构(区域) + 已授权项目 id 列表 */
    @GetMapping("/{id}/data-scope")
    public R<Map<String, Object>> getDataScope(@PathVariable Long id) {
        assertCanManage(id);
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
        // 这两条曾经是提权守卫的侧门：改不了超管的 userType，却能把超管的 ORG_ID 清空或挪到别县。
        // ORG_ID 是县域隔离的根（DataScopeResolver 按它的 orgCode 前 6 位推县），动它等价于动权限。
        assertCanManage(id);
        assertOrgAssignable(req.getOrgId());
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

    // ===== 提权防线 =====
    // /sys/user 挂菜单 101 全方法保护，理论上只有安全管理岗能进，但「能进用户管理」≠「能把自己
    // 提成超管」。下面三条守卫把这条边界写死：拿不到 SYS_ADMIN 身份的人，既不能造超管、
    // 不能改超管、也不能把自己没有的角色发出去（否则给自己加一个财政岗就绕过了全部菜单授权）。

    private boolean currentIsAdmin() {
        Long uid = UserContext.currentUserId();
        if (uid == null) return false;
        SysUser me = mapper.selectById(uid);
        return me != null && "SYS_ADMIN".equals(me.getUserType());
    }

    /** 只有超管能造/改出 userType=SYS_ADMIN 的账号。 */
    private void assertCanGrantUserType(String userType) {
        if ("SYS_ADMIN".equals(userType) && !currentIsAdmin())
            throw new BizException(403, "无权设置系统管理员身份");
    }

    /** 只有超管能改动一个超管账号（含改资料、改角色、重置口令、删除）。 */
    private void assertCanManage(Long targetId) {
        if (targetId == null || currentIsAdmin()) return;
        SysUser t = mapper.selectById(targetId);
        if (t != null && "SYS_ADMIN".equals(t.getUserType()))
            throw new BizException(403, "无权操作系统管理员账号");
    }

    /**
     * 非超管只能把人挂到自己看得见的机构上。否则「分配数据」就成了跨县投放账号的口子：
     * 把一个自己能改密码的账号挂到别县，再用它登录，就拿到了那个县的全部数据。
     * 判据复用 DataScopeResolver，与机构树/列表同一套口径。
     */
    private void assertOrgAssignable(Long orgId) {
        if (orgId == null || currentIsAdmin()) return;   // null=显式清空机构，那是收窄不是扩权
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null) throw new BizException("机构不存在");
        if (!dataScope.orgVisible(org)) throw new BizException(403, "无权分配到该机构（非本县）");
    }

    /** 非超管只能分配自己已持有的角色，杜绝「自助升级」。 */
    private void assertAssignableRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty() || currentIsAdmin()) return;
        Long uid = UserContext.currentUserId();
        Set<Long> mine = uid == null ? Set.of() : userRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, uid))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        for (Long rid : roleIds) {
            if (rid != null && !mine.contains(rid))
                throw new BizException(403, "无权分配未持有的角色");
        }
    }

    /**
     * 账号唯一（大小写不敏感）。库里已由 V44 的函数唯一索引兜底，这里先查一次是为了
     * 给出「账号已存在」而不是一条 ORA-00001 的 500。excludeId 用于编辑时排除自己。
     */
    private void assertUsernameFree(String username, Long excludeId) {
        if (username == null || username.isBlank()) throw new BizException("请填写账号");
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>()
                .apply("UPPER(USERNAME) = {0}", username.trim().toUpperCase())
                .ne(excludeId != null, SysUser::getId, excludeId);
        Long n = mapper.selectCount(w);
        if (n != null && n > 0) throw new BizException("账号已存在");
    }

    /** 一次性初始/重置口令：10 位无歧义字符（去掉 0O1lI），够强又能口头转述。 */
    private static String randomPassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
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
