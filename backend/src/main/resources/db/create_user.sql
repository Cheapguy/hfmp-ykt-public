-- 以 sysdba 运行：创建一卡通专用账号 ykt/ykt
-- 幂等：若用户已存在先删（仅删该独立用户，不影响其他 schema）
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
CREATE USER ykt IDENTIFIED BY ykt;
GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE SESSION, UNLIMITED TABLESPACE TO ykt;
EXIT;
