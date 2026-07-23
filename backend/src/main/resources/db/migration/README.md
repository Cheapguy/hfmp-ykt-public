# 迁移目录（V31 起，自建 ledger 管理）

本目录放 **受版本追踪的新迁移**。为什么不用 Flyway：Flyway 社区版已停止支持
Oracle 11g（报 "Oracle 11.2 is no longer supported by Flyway Community Edition"，
需 Teams 付费版）。故自建轻量 ledger（`com.bosi.ykt.migration.DbMigrator`），
纯 JDBC，拿到与 Flyway 相同的能力：版本追踪 / 幂等有序应用 / 已应用脚本篡改检测。

历史迁移 `../migrate_01~30_*.sql` 是 baseline 之前的手工 sqlplus 脚本，
**保留归档、不由 ledger 管理、不要移进来、不要改名**。

## 分界与 baseline

- 生产已按顺序跑完 `migrate_01~30`，状态即"当前"。
- `ykt.migrate.baseline` 默认 **30**：首次运行只往 `SCHEMA_MIGRATION_HISTORY`
  打一行 baseline（version=30），**绝不重跑 01~30**。
- 版本号 ≤ baseline 的文件一律跳过（防误放旧号进目录）。

## 新迁移命名

```
V31__<下划线描述>.sql      如 V31__add_beneficiary_idcard_index.sql
V32__<...>.sql
```

- 版本号从 **31** 连续递增，不复用、不回退。
- 一个迁移一件事。文件一旦被任何环境应用过就 **不可再改**：ledger 记了 SHA-256
  checksum，改了下次运行会直接中止报错（等价 Flyway validate）。要改就加更高版本。
- **JDBC 友好语法**：不要 `@@` `SET DEFINE OFF` `EXIT` `WHENEVER SQLERROR` 这些
  sqlplus 专属命令。普通语句以行尾 `;` 结束；PL/SQL 匿名块 / `CREATE OR REPLACE
  PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE` 以 **单独一行 `/`** 结束。
- 注释只写整行，别在语句尾部跟 `-- xx`（历史坑，见记忆 sqlplus 行尾注释）。
- Oracle DDL 无事务回滚，务必自带幂等守卫（查 `USER_TAB_COLS` 判存在再 ALTER），
  沿用 `../migrate_20_schema_backfill.sql` 的写法。

## 执行（部署流水线手动，应用启动不自动迁移）

随 fat jar 执行，Web 关掉不占端口：

```bash
java -jar target/ykt-backend-1.0.0.jar --ykt.migrate=true --spring.main.web-application-type=none
```

生产覆盖连接（与应用同一套环境变量）：

```bash
YKT_DB_URL=jdbc:oracle:thin:@<host>:1521/<svc> YKT_DB_USER=<u> YKT_DB_PWD=<p> \
  java -jar ykt-backend-1.0.0.jar --ykt.migrate=true --spring.main.web-application-type=none
```

退出码 `0`=成功（含"已最新"），`1`=失败并已中止。

> 不带 `--ykt.migrate=true` 的正常启动 **不会** 触发迁移——`MigrationRunner` 直接 return。
> 迁移是独立运维动作，坏迁移不会 brick 服务启动。

## history 表

`SCHEMA_MIGRATION_HISTORY`：VERSION(PK) / DESCRIPTION / SCRIPT / CHECKSUM /
INSTALLED_BY / INSTALLED_ON / EXECUTION_MS / SUCCESS。查"这库跑到第几号"：

```sql
SELECT VERSION, DESCRIPTION, INSTALLED_ON, SUCCESS FROM SCHEMA_MIGRATION_HISTORY ORDER BY VERSION;
```
