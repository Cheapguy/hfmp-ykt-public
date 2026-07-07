SET LINESIZE 200
SET PAGESIZE 100
SELECT (SELECT COUNT(*) FROM user_tables) tables,
       (SELECT COUNT(*) FROM sys_user)    users,
       (SELECT COUNT(*) FROM sys_menu)    menus,
       (SELECT COUNT(*) FROM ykt_project) projects
FROM dual;
SELECT username, real_name, user_type FROM sys_user ORDER BY id;
SELECT id, menu_name FROM sys_menu WHERE parent_id = 0 ORDER BY sort_no;
EXIT;
