-- 把「州级财政」角色的 ID 规整成 14，并清掉抬高 MAX(ID) 的冒烟测试残留角色。
--
-- 【为什么会跑偏】V39 用 `SELECT NVL(MAX(ID),0)+1 FROM SYS_ROLE` 取新 ID，而表里躺着一条
-- 冒烟测试留下的软删角色 SMOKE_TMP（雪花 ID 2074756989451284482, DELETED=1），
-- MAX(ID) 被它拉到 2.07E18，于是新角色也拿到了一个雪花 ID。
-- 功能上没毛病，但业务角色一直是小整数（1~13），代码里按角色 ID 硬编码岗位的地方
-- （如 YktProjectController.STAGE_ROLE）写一串 19 位数字既难读又易错。
--
-- 【为什么不直接改 V39】V39 已经记账成功，改文件内容会触发 DbMigrator 的 checksum 篡改检测，
-- 整个迁移链直接报错。已执行的迁移一律只增不改。
--
-- SYS_ROLE 主键上没有任何外键指向（已核对 user_constraints），所以可以直接改主键再同步两张关联表。
-- 幂等：仅在「角色存在 且 ID 不是 14 且 14 空闲」时动手；软删角色按 DELETED=1 且无任何绑定才清。

DECLARE
  v_old NUMBER;
  v_cnt NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM SYS_ROLE WHERE ROLE_CODE = 'ROLE_FIN_STATE' AND ID <> 14;
  IF v_cnt = 1 THEN
    SELECT COUNT(*) INTO v_cnt FROM SYS_ROLE WHERE ID = 14;
    IF v_cnt = 0 THEN
      SELECT ID INTO v_old FROM SYS_ROLE WHERE ROLE_CODE = 'ROLE_FIN_STATE';
      UPDATE SYS_ROLE      SET ID      = 14 WHERE ID      = v_old;
      UPDATE SYS_ROLE_MENU SET ROLE_ID = 14 WHERE ROLE_ID = v_old;
      UPDATE SYS_USER_ROLE SET ROLE_ID = 14 WHERE ROLE_ID = v_old;
    END IF;
  END IF;
END;
/

-- 清掉冒烟测试残留：软删状态且没有任何用户/菜单绑定的临时角色。
-- 留着它，以后每一处 MAX(ID)+1 都会继续拿到雪花 ID。
DELETE FROM SYS_ROLE
 WHERE ROLE_CODE = 'SMOKE_TMP'
   AND NVL(DELETED, 0) = 1
   AND NOT EXISTS (SELECT 1 FROM SYS_USER_ROLE ur WHERE ur.ROLE_ID = SYS_ROLE.ID)
   AND NOT EXISTS (SELECT 1 FROM SYS_ROLE_MENU rm WHERE rm.ROLE_ID = SYS_ROLE.ID);
