-- 州（市）级财政账号 + 发放类型老数据归一。
--
-- 【为什么要建这个账号】
-- 「市级账户只能选择市级」这条规则判的是账号所属的行政层级。原先库里 8 个 finance_* 全挂在
-- 县市财政局下（甲县财政局 → 甲县/COUNTY，甲县是县级市，名字带"市"但不是地级），
-- STATE 那一级（某某州）底下一个账号都没有，规则等于没有承载体。
-- 这里补上「某某州财政局」及其经办账号，对应生产环境里某市财政局那个角色。
--
-- 层级：某某州(990000/STATE) -> 某某州财政局(DEPT) -> finance_state
-- 机构编码沿用县市财政局的构造法：行政区划码 + '990002'（甲县财政局是 990001990002）。
--
-- 密码不硬编码 bcrypt 串，从已有的同岗账号抄——硬编码一段 hash 进迁移文件，
-- 等于把口令的哈希留在版本库里，且改口令策略后无法同步。
--
-- ⚠ 参照账号可能不存在：finance_lxs 只由归档的 migrate_14 建，而 ledger 的 baseline=30 决定了
-- 空库重建时那批归档脚本根本不跑，seed.sql 里也没有任何 finance 账号。
-- 原写法 INSERT..SELECT 命中 0 行却不报错，继续往下插 SYS_USER_ROLE 指向不存在的用户，
-- 再由 V39 的 SELECT ID INTO 抛 ORA-01403，把 V39~V43 整条链带崩（州级角色停在 DATA_SCOPE='ALL'，
-- 即全省可见）。故这里改为：找不到参照账号就跳过建账号，机构照建，链路继续。
--
-- 幂等：机构/账号/授权都先判存在；发放类型回填条件自带过滤。

DECLARE
  v_org  NUMBER;
  v_user NUMBER;
  v_cnt  NUMBER;
BEGIN
  -- ① 州财政局机构
  SELECT COUNT(*) INTO v_cnt FROM SYS_ORG WHERE ORG_CODE = '990000990002';
  IF v_cnt = 0 THEN
    SELECT NVL(MAX(ID), 700000000000) + 1 INTO v_org FROM SYS_ORG;
    INSERT INTO SYS_ORG (ID, PARENT_ID, ORG_CODE, ORG_NAME, ORG_TYPE, SORT_NO, STATUS, TENANT_ID, DELETED)
    VALUES (v_org, 990000, '990000990002', '某某州财政局', 'DEPT', 20, 1, 1, 0);
  ELSE
    SELECT ID INTO v_org FROM SYS_ORG WHERE ORG_CODE = '990000990002';
  END IF;

  -- ② 州级财政经办账号
  SELECT COUNT(*) INTO v_cnt FROM SYS_USER WHERE USERNAME = 'finance_state';
  IF v_cnt > 0 THEN
    SELECT ID INTO v_user FROM SYS_USER WHERE USERNAME = 'finance_state';
    -- 机构可能在早期版本挂错，重跑时纠正到州财政局
    UPDATE SYS_USER SET ORG_ID = v_org WHERE ID = v_user;
  ELSE
    -- 参照账号：优先 finance_lxs，否则任一在用的财政岗账号；都没有就不建账号
    SELECT COUNT(*) INTO v_cnt FROM SYS_USER
     WHERE (USERNAME = 'finance_lxs' OR USER_TYPE = 'FINANCE') AND NVL(DELETED, 0) = 0;
    IF v_cnt > 0 THEN
      SELECT NVL(MAX(ID), 700000) + 1 INTO v_user FROM SYS_USER;
      INSERT INTO SYS_USER (ID, ORG_ID, USERNAME, PASSWORD, REAL_NAME, USER_TYPE, STATUS, TENANT_ID, DELETED)
      SELECT v_user, v_org, 'finance_state', p.PASSWORD, '某某州财政局', 'FINANCE', 1, 1, 0
        FROM (SELECT PASSWORD FROM SYS_USER
               WHERE (USERNAME = 'finance_lxs' OR USER_TYPE = 'FINANCE') AND NVL(DELETED, 0) = 0
               ORDER BY CASE WHEN USERNAME = 'finance_lxs' THEN 0 ELSE 1 END, ID) p
       WHERE ROWNUM = 1;
    ELSE
      v_user := NULL;   -- 空库重建：账号交给 seed 或人工补，别让链在这里断
    END IF;
  END IF;

  -- ③ 绑定预算执行-集中支付(role 3)，与其余 finance_* 同岗
  IF v_user IS NOT NULL THEN
    SELECT COUNT(*) INTO v_cnt FROM SYS_USER_ROLE WHERE USER_ID = v_user AND ROLE_ID = 3;
    IF v_cnt = 0 THEN
      INSERT INTO SYS_USER_ROLE (ID, USER_ID, ROLE_ID)
      VALUES ((SELECT NVL(MAX(ID), 0) + 1 FROM SYS_USER_ROLE), v_user, 3);
    END IF;
  END IF;
END;
/

-- ④ 发放类型归一：只保留「到户」「到人」两种，老数据里的「一卡通发放」是脚手架阶段的臆造值。
-- 补贴默认发到户，故统一为「到户」；本就为空的不动（非必填历史数据，不替用户臆断）。
UPDATE YKT_PROJECT SET GRANT_TYPE = '到户' WHERE GRANT_TYPE = '一卡通发放';

-- 兜底另外两个同批臆造值，防某些环境已经录过
UPDATE YKT_PROJECT SET GRANT_TYPE = '到户' WHERE GRANT_TYPE IN ('社会化发放', '现金发放');
