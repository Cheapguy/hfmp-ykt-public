-- 补贴项目管理独立成一级菜单 + 审核链角色退场。
--
-- 起因：项目审核链由 5 棒简化为 3 棒——县财政录入(role 3 finance) → 省财政厅业务处室(role 9)
--       → 省财政厅农业处(role 10) 终审。中间的市州财政综合岗、县区财政项目录入/审核岗、
--       信息处核定追踪代码这几棒取消，对应角色与账号一并退场。
--
-- 三块改动：
--   ① 新建一级菜单「补贴项目管理」(id=9)，把 301/302/303/304 从「主管部门」(id=3) 下移过来。
--   ② 授权重排：finance 拿维护/纳入挂接/查询；省厅两岗只拿审核 + 查询（不给维护——省厅不录入县项目）。
--   ③ 退场角色 8/11/12/13 停用并清空菜单授权；只持退场角色的账号(fin_audit_*/sz_audit/prov_info)一并停用。
--
-- 不删角色行、不删用户行、不解绑 SYS_USER_ROLE：审核日志里留着这些岗位的历史操作记录，
-- 删掉会让「审核历史」查不到操作人归属。停用足够挡住登录与鉴权，且可一条 UPDATE 回滚。
--
-- ⚠ 菜单授权必须显式清 SYS_ROLE_MENU：AuthController 拉用户菜单时只 join SYS_USER_ROLE，
--   不看角色的 STATUS——光把角色停用，菜单照样发。
--
-- 幂等：菜单按 ID MERGE；授权 INSERT 前置 NOT EXISTS；停用条件自带过滤，重复执行零改动。

-- ① 一级菜单
MERGE INTO SYS_MENU t
USING (SELECT 9 id, 0 pid, 'project' code, '补贴项目管理' nm, 'M' ty, 18 sn, 1 vi FROM DUAL) s
   ON (t.ID = s.id)
WHEN MATCHED THEN UPDATE SET t.MENU_NAME = s.nm, t.PARENT_ID = s.pid, t.SORT_NO = s.sn, t.VISIBLE = s.vi
WHEN NOT MATCHED THEN INSERT (ID, PARENT_ID, MENU_CODE, MENU_NAME, MENU_TYPE, SORT_NO, VISIBLE)
                      VALUES (s.id, s.pid, s.code, s.nm, s.ty, s.sn, s.vi);

-- ①' 补建 304（补贴项目查询）。seed.sql 里只有 301/302/303/305/306/307，
-- 缺它时下面的 reparent 是空操作、
-- 授权指向不存在的菜单，结果「补贴项目查询」「补贴项目审核」有路由却没有入口。
-- 用 MERGE 而不是 INSERT：活库里它们已存在，重复插会撞主键。
MERGE INTO SYS_MENU t
USING (SELECT 304 id, '/dept/project-query' pth, '补贴项目查询' nm FROM DUAL
       UNION ALL
       SELECT 302, '/dept/project-audit', '补贴项目审核' FROM DUAL) s
   ON (t.ID = s.id)
WHEN NOT MATCHED THEN INSERT (ID, PARENT_ID, MENU_CODE, MENU_NAME, MENU_TYPE, PATH, SORT_NO, VISIBLE)
                      VALUES (s.id, 9, 'project', s.nm, 'C', s.pth, 13, 1);

-- 四个子菜单改挂到 9 下。SORT_NO 重排成 11/12/13/14，原值(31/33/34/12)是在「主管部门」里的次序。
-- 注释一律整行：DbMigrator 判完行尾注释后是用 buf.lastIndexOf(";") 找分号删的，
-- 行尾注释里若出现分号会被删错位置，语句带着尾分号进 JDBC 直接 ORA-00911。
-- 301 补贴项目维护
UPDATE SYS_MENU SET PARENT_ID = 9, SORT_NO = 11 WHERE ID = 301;
-- 303 项目纳入及挂接
UPDATE SYS_MENU SET PARENT_ID = 9, SORT_NO = 12 WHERE ID = 303;
-- 304 补贴项目查询
UPDATE SYS_MENU SET PARENT_ID = 9, SORT_NO = 13 WHERE ID = 304;
-- 302 补贴项目审核（公库编号，私库那侧是 312）
UPDATE SYS_MENU SET PARENT_ID = 9, SORT_NO = 14 WHERE ID = 302;

-- ② 授权重排
-- finance(role 3)：录入岗，拿 父菜单 + 维护 + 纳入挂接 + 查询（不含审核——不能自己审自己录的）
INSERT INTO SYS_ROLE_MENU (ID, ROLE_ID, MENU_ID)
SELECT (SELECT NVL(MAX(ID), 0) FROM SYS_ROLE_MENU) + ROWNUM, 3, m
  FROM (SELECT 9 m FROM DUAL UNION ALL SELECT 301 FROM DUAL
        UNION ALL SELECT 303 FROM DUAL UNION ALL SELECT 304 FROM DUAL) x
 WHERE NOT EXISTS (SELECT 1 FROM SYS_ROLE_MENU rm WHERE rm.ROLE_ID = 3 AND rm.MENU_ID = x.m);

-- 省财政厅业务处室(9) / 农业处(10)：父菜单 + 审核 + 查询
INSERT INTO SYS_ROLE_MENU (ID, ROLE_ID, MENU_ID)
SELECT (SELECT NVL(MAX(ID), 0) FROM SYS_ROLE_MENU) + ROWNUM, r, m
  FROM (SELECT 9 r FROM DUAL UNION ALL SELECT 10 FROM DUAL) rr,
       (SELECT 9 m FROM DUAL UNION ALL SELECT 302 FROM DUAL UNION ALL SELECT 304 FROM DUAL) x
 WHERE NOT EXISTS (SELECT 1 FROM SYS_ROLE_MENU rm WHERE rm.ROLE_ID = rr.r AND rm.MENU_ID = x.m);

-- 省厅两岗撤掉「补贴项目维护」(301) 与「主管部门」目录(3)：
-- 301 能新增/删除县里的项目，审核岗不该有；3 的子菜单已被移走，留着只会显示一个空目录。
DELETE FROM SYS_ROLE_MENU WHERE ROLE_ID IN (9, 10) AND MENU_ID IN (3, 301);

-- ③ 退场：清授权 + 停用角色 + 停用只持这些角色的账号
DELETE FROM SYS_ROLE_MENU WHERE ROLE_ID IN (8, 11, 12, 13);

UPDATE SYS_ROLE SET STATUS = 0 WHERE ID IN (8, 11, 12, 13) AND NVL(STATUS, 1) <> 0;

-- 只停用「所有角色都已退场」的账号：finance_* 同时持 role 3，不能被误停。
UPDATE SYS_USER SET STATUS = 0
 WHERE NVL(STATUS, 1) <> 0
   AND ID IN (SELECT ur.USER_ID FROM SYS_USER_ROLE ur GROUP BY ur.USER_ID
               HAVING COUNT(*) = COUNT(CASE WHEN ur.ROLE_ID IN (8, 11, 12, 13) THEN 1 END));
