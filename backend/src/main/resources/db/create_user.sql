-- 以 sysdba 运行：创建一卡通专用账号。
-- 幂等：若用户已存在先删（仅删该独立用户，不影响其他 schema）
--
-- ⚠ 口令：下面的 ykt/ykt 只适用于本地开发库。任何联网/共用环境都必须先改掉，
--   最省事的做法是建完立刻 `ALTER USER ykt IDENTIFIED BY "<强口令>";`，
--   再把同一个值配到应用的 YKT_DB_PWD 环境变量（application.yml 已从环境变量取）。
--
-- ⚠ 权限：这里给的是「能建表也能读写业务数据」的一个账号，够跑起来，但不是最小权限。
--   正式环境建议拆成两个：DDL 账号（跑 schema/migration）与应用账号（只有对象上的
--   SELECT/INSERT/UPDATE/DELETE，无 CREATE ANY），应用连的是后者。
SET SERVEROUTPUT ON
DECLARE
  v_cnt NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_cnt FROM dba_users WHERE username = 'YKT';
  IF v_cnt > 0 THEN
    EXECUTE IMMEDIATE 'DROP USER ykt CASCADE';
  END IF;
END;
/
CREATE USER ykt IDENTIFIED BY ykt DEFAULT TABLESPACE USERS TEMPORARY TABLESPACE TEMP;
-- CONNECT 已含 CREATE SESSION，不再重复授予。
-- 不给 UNLIMITED TABLESPACE：那是「在所有表空间上不限量」的系统权限，
-- 一个业务账号写满任意表空间就能把整库拖下水；改成只在自己的默认表空间上放开配额。
GRANT CONNECT, RESOURCE, CREATE VIEW TO ykt;
ALTER USER ykt QUOTA UNLIMITED ON USERS;
EXIT;
