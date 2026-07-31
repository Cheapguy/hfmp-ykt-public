-- 退回后「原岗续审」：记住退回时批次所停的审核岗，乡镇经办改完再送审直接回该岗，
-- 不必重跑 乡镇审核→部门经办→部门领导 全链。REJECT_STAGE 为空=从未被退回，送审按原口径进乡镇审核。
-- 幂等：列存在则跳过。JDBC 友好，无 sqlplus 专属命令。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_BATCH' AND COLUMN_NAME = 'REJECT_STAGE';
  IF v = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_BATCH ADD (REJECT_STAGE VARCHAR2(32))';
  END IF;
END;
/
