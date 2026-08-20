-- 代码审核 #36：项目编码用「查 MAX 再 +1」生成，中间没有任何互斥。
-- 两个县同时终审，各自读到同一个 MAX，就会发出两个一模一样的 PROJECT_CODE。
-- 而项目编码是县域可见性的判据（'9'+县码 前缀），也是对外的业务主键，撞号很难事后察觉。
-- 这里加唯一索引做最后一道保证，应用侧再配合「撞了就重算重试」（见 YktProjectController）。
-- 同样用函数索引排除逻辑删除行：CASE 为 NULL 不进索引。
-- 执行前请确认目标库中活行项目的 PROJECT_CODE 无重复，否则建索引会失败。
DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM USER_INDEXES WHERE INDEX_NAME = 'UX_PROJECT_CODE';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX UX_PROJECT_CODE ON YKT_PROJECT (CASE WHEN DELETED = 0 THEN PROJECT_CODE END)';
  END IF;
END;
/
