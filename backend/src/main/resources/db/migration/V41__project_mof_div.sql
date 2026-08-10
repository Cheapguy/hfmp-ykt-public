-- 补贴项目补「所属行政区划」列。
--
-- 导入生产项目库时带进来的 mof_div_code（如 990008000 辛县、990000000 省本级），
-- 用于列表展示项目归属，也是排查可见性问题时的直接依据。
--
-- 注意：它不是权限判据。可见性仍走 PROJECT_CODE 前缀（DataScopeResolver.applyProject），
-- 因为本系统自己生成的项目编码就是 '9'+县码+序号，两套判据只能有一套是真源，
-- 否则手工改了区划码就能越权。这一列纯展示，写错了也不影响隔离。
--
-- 幂等：列存在则跳过。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_PROJECT' AND COLUMN_NAME = 'MOF_DIV_CODE';
  IF v = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_PROJECT ADD (MOF_DIV_CODE VARCHAR2(20 CHAR))';
  END IF;

  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_PROJECT' AND COLUMN_NAME = 'MOF_DIV_NAME';
  IF v = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_PROJECT ADD (MOF_DIV_NAME VARCHAR2(64 CHAR))';
  END IF;
END;
/
