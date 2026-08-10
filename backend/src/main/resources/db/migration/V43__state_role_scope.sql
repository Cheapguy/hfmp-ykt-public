-- 州级财政角色的数据范围由 ALL 收窄为 STATE。
--
-- 起因：项目库导入全省 1092 条后，ALL 让某某州财政局连外州某县、外州某市、外州某市的项目都看得见。
-- 州财政局只管本州——甲县 + 乙县~辛县共 8 个县市，加州本级自己，一共 9 个编码前缀。
--
-- STATE 不是 DataScopeResolver.Scope 的枚举值（那个枚举被 10 处判断引用，加值要逐处决定归属）。
-- 解析时 STATE 仍落到 Scope.ALL，另由 Ctx.stateScoped 标记，只在项目可见性 applyProject 生效：
-- 乡镇、批次、花名册这些维度上州级本来就等于全域，本系统只服务某某州一个州。
--
-- 幂等：条件自带过滤。

UPDATE SYS_ROLE SET DATA_SCOPE = 'STATE'
 WHERE ROLE_CODE = 'ROLE_FIN_STATE' AND NVL(DATA_SCOPE, ' ') <> 'STATE';
