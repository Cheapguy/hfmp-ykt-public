-- 政策文件改多附件。原先 YKT_PROJECT.POLICY_FILE 单字段只存一个下载地址，生产是一张附件表
-- （序号/文件名称/文件大小/上传人/下载/重新上传/预览/删除），一个项目可挂多份政策文件。
--
-- 关联时机：新增项目时还没有 PROJECT_ID，所以附件不是即传即挂——前端先传文件拿到落盘信息，
-- 随项目表单一起提交，后端在保存项目后按 PROJECT_ID 整体重写该项目的附件行。
-- 因此本表没有「孤儿附件」状态，也不需要临时归属或定时清理。
--
-- 旧 POLICY_FILE 字段保留不删：历史项目的单附件地址还在里面，迁移进本表后仍留作只读回退。
-- 幂等：表存在则整段跳过；回迁按 PROJECT_ID 去重，重复执行零新增。

DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TABLES WHERE TABLE_NAME = 'YKT_PROJECT_FILE';
  IF v = 0 THEN
    EXECUTE IMMEDIATE q'[
      CREATE TABLE YKT_PROJECT_FILE (
        ID          NUMBER(20)   NOT NULL,
        PROJECT_ID  NUMBER(20)   NOT NULL,
        FILE_NAME   VARCHAR2(255 CHAR) NOT NULL,
        FILE_SIZE   NUMBER(20)   DEFAULT 0,
        FILE_URL    VARCHAR2(500 CHAR) NOT NULL,
        UPLOAD_BY   NUMBER(20),
        UPLOAD_NAME VARCHAR2(50 CHAR),
        UPLOAD_TIME TIMESTAMP(6),
        SORT_NO     NUMBER(10)   DEFAULT 0,
        CONSTRAINT PK_YKT_PROJECT_FILE PRIMARY KEY (ID)
      )]';
    -- 外键索引：列表按项目查附件，且父表 YKT_PROJECT 的 DML 不该锁本表
    EXECUTE IMMEDIATE 'CREATE INDEX IDX_PROJECT_FILE_PID ON YKT_PROJECT_FILE (PROJECT_ID)';
  END IF;
END;
/

-- 历史单附件回迁。只迁看得出是上传地址的（/files/preview 开头）——旧数据里 POLICY_FILE 有一部分
-- 是手工填的纯文件名文本，没有可下载的落盘文件，迁进来会得到一行点不开的附件。
-- 文件名从 URL 的 fn 参数取；取不到就回落成「政策文件附件」。大小填 0：老数据没记过字节数，
-- 与其猜一个假值，不如让前端把 0 显示成「—」。
DECLARE
  v NUMBER;
BEGIN
  SELECT COUNT(*) INTO v FROM USER_TABLES WHERE TABLE_NAME = 'YKT_PROJECT_FILE';
  IF v > 0 THEN
    EXECUTE IMMEDIATE q'[
      INSERT INTO YKT_PROJECT_FILE (ID, PROJECT_ID, FILE_NAME, FILE_SIZE, FILE_URL, UPLOAD_NAME, UPLOAD_TIME, SORT_NO)
      SELECT p.ID, p.ID,
             NVL(UTL_URL.UNESCAPE(REGEXP_SUBSTR(p.POLICY_FILE, 'fn=([^&]+)', 1, 1, NULL, 1), 'AL32UTF8'),
                 '政策文件附件'),
             0, p.POLICY_FILE, '历史数据', p.CREATE_TIME, 1
        FROM YKT_PROJECT p
       WHERE p.POLICY_FILE LIKE '/hfmp-ykt/api/files/preview/%'
         AND NOT EXISTS (SELECT 1 FROM YKT_PROJECT_FILE f WHERE f.PROJECT_ID = p.ID)]';
  END IF;
END;
/
