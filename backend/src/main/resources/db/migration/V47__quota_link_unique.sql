-- 代码审核 #32：同一个项目可以把同一个指标挂接两次。
-- 挂两条，computeDeduction 就把这个指标的额度算两份，支付时按虚高的可用额去扣；
-- 指标表上的 SQL 余额守卫会挡住扣成负数，但那时整单已经回滚，
-- 现象是「额度明明够却发不出去」，没人会想到根因在挂接表里有重复行。
-- 应用侧已在 link() 做去重（含请求体自身重复），这里补一道库级硬保证。
-- 部分唯一索引：CASE 为 NULL 的行不进索引，于是逻辑删除行与未挂指标的行都不参与约束。
-- 执行前请确认目标库中 (PROJECT_ID, INDICATOR_ID) 活行无重复，否则建索引会失败。
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'UX_PROJECT_QUOTA_IND';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_PROJECT_QUOTA_IND ON YKT_PROJECT_QUOTA ('
      || 'CASE WHEN DELETED = 0 AND INDICATOR_ID IS NOT NULL THEN PROJECT_ID END, '
      || 'CASE WHEN DELETED = 0 AND INDICATOR_ID IS NOT NULL THEN INDICATOR_ID END)';
  END IF;
END;
/
