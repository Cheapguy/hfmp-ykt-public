-- 以 ykt 身份运行：建表 + 灌种子。@@ 相对当前脚本目录定位。
WHENEVER SQLERROR CONTINUE
SET DEFINE OFF
@@schema.sql
@@seed.sql
EXIT;
