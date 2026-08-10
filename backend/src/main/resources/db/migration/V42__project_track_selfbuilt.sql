-- 补贴项目补「项目追踪名称」「是否自建项目」两列，对齐生产「补贴项目查询·本省项目」列表。
--
-- 生产该列表的列是：项目编码 / 项目名称 / 项目追踪代码 / 项目追踪名称 / 主管部门 / 业务科室 /
-- 发放类型 / 是否自建项目。前者本系统已有 TRACE_CODE，后两项一直缺列。
--
-- TRACK_PRO_NAME：追踪代码对应的资金名称（如 17Z175080010001 -> 困难群众救助补助资金）。
--   多个补贴项目会共用同一笔上级资金，所以它跟 TRACE_CODE 一样是「多对一」的冗余展示字段。
-- IS_SELF_BUILT：'是'/'否'。省级标准项目（7 位国标码）为否，各地自建项目（9+区划码）为是。
--   存中文而不是 0/1，是因为生产就是直接显示这两个字，列表不需要再做一层映射。
--
-- 幂等：逐列判断存在性。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_PROJECT' AND COLUMN_NAME = 'TRACK_PRO_NAME';
  IF v = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_PROJECT ADD (TRACK_PRO_NAME VARCHAR2(200 CHAR))';
  END IF;

  SELECT COUNT(*) INTO v FROM USER_TAB_COLUMNS
   WHERE TABLE_NAME = 'YKT_PROJECT' AND COLUMN_NAME = 'IS_SELF_BUILT';
  IF v = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE YKT_PROJECT ADD (IS_SELF_BUILT VARCHAR2(4 CHAR))';
  END IF;
END;
/

-- 兜底回填：本系统内新建的项目（编码 '9'+县码+5位序列）一律算自建，
-- 导入的生产数据会在导入脚本里按 projtype 显式赋值，不依赖这条。
UPDATE YKT_PROJECT SET IS_SELF_BUILT = '是'
 WHERE IS_SELF_BUILT IS NULL AND REGEXP_LIKE(PROJECT_CODE, '^9[0-9]{11}$');
