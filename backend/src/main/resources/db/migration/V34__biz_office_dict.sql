-- 业务处室（财政归口处室）字典。补贴项目维护表单的「业务处室」下拉数据源，取自生产机构接口
-- （某市财政局 province=620500000）的 21 个内设科室，按 code 顺序灌入。
--
-- 为什么不复用 YKT_OFFICE：那张表是「省级处室」（厅领导/综合处/税政处…），原本供市州财政综合岗
-- 审核时选定归口处室用。审核链简化后该岗退场，归口处室改由录入时填的业务处室直接充当——
-- 两者层级不同（省财政厅处室 vs 市/县财政局科室），混在一张表里会让历史 PIVOT_OFFICE_NAME 语义错乱。
--
-- 幂等：表存在则跳过建表；数据按 OFFICE_CODE MERGE，重复执行只更新名称与排序，不产生重复行。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TABLES WHERE TABLE_NAME = 'YKT_BIZ_OFFICE';
  IF v = 0 THEN
    EXECUTE IMMEDIATE q'[
      CREATE TABLE YKT_BIZ_OFFICE (
        ID          NUMBER(20)   NOT NULL,
        OFFICE_CODE VARCHAR2(20 CHAR) NOT NULL,
        OFFICE_NAME VARCHAR2(100 CHAR) NOT NULL,
        SORT_NO     NUMBER(10)   DEFAULT 0,
        STATUS      NUMBER(1)    DEFAULT 1,
        CONSTRAINT PK_YKT_BIZ_OFFICE PRIMARY KEY (ID)
      )]';
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_BIZ_OFFICE_CODE ON YKT_BIZ_OFFICE (OFFICE_CODE)';
  END IF;
END;
/

-- 21 个归口处室。MERGE 而非 INSERT：开发库可能已手工建过，重跑不能撞主键。
MERGE INTO YKT_BIZ_OFFICE t
USING (
  SELECT '0001' c, '局领导'               n, 1  s FROM DUAL UNION ALL
  SELECT '0002', '预算科',                    2  FROM DUAL UNION ALL
  SELECT '0003', '国库科',                    3  FROM DUAL UNION ALL
  SELECT '0004', '综合科',                    4  FROM DUAL UNION ALL
  SELECT '0005', '行政政法科',                5  FROM DUAL UNION ALL
  SELECT '0006', '教育事业科',                6  FROM DUAL UNION ALL
  SELECT '0007', '科技文化科',                7  FROM DUAL UNION ALL
  SELECT '0008', '社会保障科',                8  FROM DUAL UNION ALL
  SELECT '0009', '经济建设科',                9  FROM DUAL UNION ALL
  SELECT '0010', '农业科',                    10 FROM DUAL UNION ALL
  SELECT '0011', '自然资源和生态环境科',      11 FROM DUAL UNION ALL
  SELECT '0012', '资产管理科',                12 FROM DUAL UNION ALL
  SELECT '0013', '政府债务管理科',            13 FROM DUAL UNION ALL
  SELECT '0014', '国际科',                    14 FROM DUAL UNION ALL
  SELECT '0015', '政府采购管理科',            15 FROM DUAL UNION ALL
  SELECT '0016', '绩效监督科',                16 FROM DUAL UNION ALL
  SELECT '0017', '信息科',                    17 FROM DUAL UNION ALL
  SELECT '0018', '预算审核中心',              18 FROM DUAL UNION ALL
  SELECT '0019', '地方金融科',                19 FROM DUAL UNION ALL
  SELECT '0020', '财会监督科',                20 FROM DUAL UNION ALL
  SELECT '0021', '国库中心',                  21 FROM DUAL
) s ON (t.OFFICE_CODE = s.c)
WHEN MATCHED THEN UPDATE SET t.OFFICE_NAME = s.n, t.SORT_NO = s.s
WHEN NOT MATCHED THEN INSERT (ID, OFFICE_CODE, OFFICE_NAME, SORT_NO, STATUS)
                      VALUES (s.s, s.c, s.n, s.s, 1);
