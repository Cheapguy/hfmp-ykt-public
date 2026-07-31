-- 更正发放批次显式标记。原先靠 BATCH_NAME 以「更正发放」开头判定，而批次名在「批次维护」页可随意改，
-- 一改前缀，前端的新增/批量填报/删除/导入/删除批次禁用与后端 6 处写守卫全部失效——更正批次的人员是从
-- 源批次固定复制来的，放开增删会破坏与源批次的对应关系（二次发放的金额核算靠它）。
-- 幂等：列存在则跳过建列；回填每次都跑（条件自带 <> 1 过滤，重复执行零改动）。
-- JDBC 友好：无 sqlplus 专属命令，不在块内 COMMIT（由 ledger 统一提交）。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_BATCH' AND COLUMN_NAME = 'IS_CORRECTION';
  IF v = 0 THEN
    -- Oracle 11g 的 ADD COLUMN DEFAULT 会把已有行一并填成 0，无需再补 UPDATE ... WHERE IS NULL
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_BATCH ADD (IS_CORRECTION NUMBER(1) DEFAULT 0)';
  END IF;

  -- 回填①名字线索。刻意放在 IF 之外：列可能已被手工 sqlplus 建过（开发库就是），
  -- 跟着 IF 跳过的话回填永远不执行。三种真实命名都要覆盖：
  --   本系统生成 = 「更正发放（第N次）--原名」前缀式
  --   外部系统   = 「202606某某补贴（更正发放）」后缀括号式（全角/半角都见过）
  -- 不用宽泛的 '%更正发放%'：避免误伤名字里偶然提到该词的普通批次（误标会直接挡住正常增删）。
  EXECUTE IMMEDIATE q'[
    UPDATE YKT_BATCH SET IS_CORRECTION = 1
     WHERE NVL(IS_CORRECTION, 0) <> 1
       AND (BATCH_NAME LIKE '更正发放%'
         OR BATCH_NAME LIKE '%（更正发放）%'
         OR BATCH_NAME LIKE '%(更正发放)%')]';

  -- 回填②结构线索，捞回「名字已被改掉」的更正批次：明细的 RETRY_TIMES（二次/三次发放计数）
  -- 只在 YktCorrectionController 重构时写入，普通批次填报恒为 null，故 >=1 即证明该批次是重构而来。
  EXECUTE IMMEDIATE q'[
    UPDATE YKT_BATCH b SET IS_CORRECTION = 1
     WHERE NVL(b.IS_CORRECTION, 0) <> 1
       AND EXISTS (SELECT 1 FROM YKT_GRANT_DETAIL d
                    WHERE d.BATCH_ID = b.ID AND NVL(d.RETRY_TIMES, 0) >= 1)]';
END;
/
