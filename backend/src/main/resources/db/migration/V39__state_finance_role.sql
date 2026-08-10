-- 州（市）级财政角色：数据范围 ALL。
--
-- 【问题】V38 建的 finance_state 是个看不到数据的空壳：它绑的是 role 3「预算执行-集中支付」，
-- 数据范围 COUNTY，而 DataScopeResolver 按机构 orgCode 前 6 位推县，推出来是 990000（州本级）——
-- 业务数据和机构字典全是 990001~990008 那 8 个县市的，一条也匹配不上：
--   · 补贴项目 / 批次列表全空
--   · 主管部门下拉（YKT_AGENCY 按县码过滤）也是空的
--
-- 【方案】新建角色「州级财政」DATA_SCOPE='ALL'，finance_state 改绑它。
-- 不改 role 3 的范围——那是 8 个县市财政账号共用的，一改成 ALL 各县就互相看得见了，县域隔离当场失效。
-- 也不动 DataScopeResolver 去识别 STATE 层级：那是县域隔离的中枢，为一个账号改中枢不划算。
--
-- 菜单从 role 3 整份复制：州财政局干的活和县财政局一样（项目录入送审 + 集中支付 + 报表），
-- 区别只在能看全州。日后 role 3 加菜单，这里要记得同步（两个角色的菜单不是自动跟随的）。
--
-- 幂等：角色/授权/绑定都先判存在，重复执行零改动。

DECLARE
  v_role NUMBER := 14;
  v_user NUMBER;
  v_cnt  NUMBER;
BEGIN
  -- ① 角色
  SELECT COUNT(*) INTO v_cnt FROM SYS_ROLE WHERE ROLE_CODE = 'ROLE_FIN_STATE';
  IF v_cnt = 0 THEN
    SELECT NVL(MAX(ID), 0) + 1 INTO v_role FROM SYS_ROLE;
    INSERT INTO SYS_ROLE (ID, ROLE_CODE, ROLE_NAME, DATA_SCOPE, STATUS, TENANT_ID, DELETED, REMARK)
    VALUES (v_role, 'ROLE_FIN_STATE', '州级财政', 'ALL', 1, 1, 0,
            '州（市）级财政账号：职责同县级财政，数据范围为全州');
  ELSE
    SELECT ID INTO v_role FROM SYS_ROLE WHERE ROLE_CODE = 'ROLE_FIN_STATE';
    -- 重跑时只保证角色是启用的，**不覆盖 DATA_SCOPE**：
    -- V43 会把它由 ALL 收窄成 STATE，这里若硬写 'ALL'，一旦本迁移因故重跑
    -- （改文件后 checksum 变了就要清记账重跑），就把 V43 的收窄悄悄冲掉，
    -- 而 V43 已记账不会再执行——州级账号又变回全省可见，且没有任何报错。
    UPDATE SYS_ROLE SET STATUS = 1 WHERE ID = v_role;
  END IF;

  -- ② 菜单：整份复制 role 3 的授权
  INSERT INTO SYS_ROLE_MENU (ID, ROLE_ID, MENU_ID)
  SELECT (SELECT NVL(MAX(ID), 0) FROM SYS_ROLE_MENU) + ROWNUM, v_role, m.MENU_ID
    FROM (SELECT DISTINCT MENU_ID FROM SYS_ROLE_MENU WHERE ROLE_ID = 3) m
   WHERE NOT EXISTS (SELECT 1 FROM SYS_ROLE_MENU rm WHERE rm.ROLE_ID = v_role AND rm.MENU_ID = m.MENU_ID);

  -- ③ finance_state 改绑：加州级财政，摘掉 role 3
  -- 必须摘干净——多角色取最宽范围虽然结果一样是 ALL，但留着 role 3 会让「角色管理」里
  -- 这个账号显示成县级岗，排查数据可见性问题时误导人。
  --
  -- ⚠ 先判存在再取 id：账号由 V38 建，而 V38 在没有参照账号的环境（空库重建，
  -- seed.sql 里一个 finance 账号都没有）会跳过建账号。裸的 SELECT INTO 在那种环境直接
  -- ORA-01403，把本迁移连同后面的 V40~V43 一起带崩，州级角色就停在 DATA_SCOPE='ALL'。
  SELECT COUNT(*) INTO v_cnt FROM SYS_USER WHERE USERNAME = 'finance_state';
  IF v_cnt > 0 THEN
    SELECT ID INTO v_user FROM SYS_USER WHERE USERNAME = 'finance_state';
    SELECT COUNT(*) INTO v_cnt FROM SYS_USER_ROLE WHERE USER_ID = v_user AND ROLE_ID = v_role;
    IF v_cnt = 0 THEN
      INSERT INTO SYS_USER_ROLE (ID, USER_ID, ROLE_ID)
      VALUES ((SELECT NVL(MAX(ID), 0) + 1 FROM SYS_USER_ROLE), v_user, v_role);
    END IF;
    DELETE FROM SYS_USER_ROLE WHERE USER_ID = v_user AND ROLE_ID = 3;
  END IF;
END;
/
