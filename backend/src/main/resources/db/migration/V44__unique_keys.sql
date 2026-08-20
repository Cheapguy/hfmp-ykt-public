-- 代码审核 #20：补两条唯一约束。
-- SYS_USER.USERNAME 此前无唯一约束，登录靠 last("AND ROWNUM=1") 兜底——两行同名账号时
-- 登进的是哪一个由执行计划决定，密码和权限都可能不是你以为的那一个。
-- YKT_PAYMENT_APPLY.APPLY_NO 是支付申请对外单号，重号会让对账无法定位到唯一一笔。
--
-- 都用函数索引把逻辑删除排除在外（DELETED=1 的历史行允许重名），
-- 这是 Oracle 上做「部分唯一索引」的标准写法：CASE 为 NULL 的行不进索引。
-- USERNAME 归一到大写：Admin 和 admin 是同一个人，不该能同时存在。
--
-- 执行前请先确认目标库中 SYS_USER.USERNAME(活行) 与 YKT_PAYMENT_APPLY.APPLY_NO 无重复，
-- 否则建唯一索引会直接失败。
-- YKT_BENEFICIARY.ID_CARD 同样值得加唯一索引，但存量库里常有重复档（同一人多次建档），
-- 需先人工并档再单独加，本迁移不碰。
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'UX_SYS_USER_USERNAME';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_SYS_USER_USERNAME ON SYS_USER (CASE WHEN DELETED = 0 THEN UPPER(USERNAME) END)';
  END IF;
END;
/

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'UX_PAY_APPLY_NO';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_PAY_APPLY_NO ON YKT_PAYMENT_APPLY (CASE WHEN DELETED = 0 THEN APPLY_NO END)';
  END IF;
END;
/
