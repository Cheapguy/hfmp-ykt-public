# AGENTS.md — hfmp-ykt 多 Agent 集群协作约定

惠民惠农财政补贴"一卡通"管理模块。SpringBoot3 + MyBatis-Plus + Oracle / Vue3 + Element Plus。
本文件是**多 Agent 并行开发的纪律**。主 Agent 派发任务前先读，子 Agent 开工前必读。

---

## 0. 运行时是单份的 —— 集群只并行"写代码"，不并行"跑服务"

| 资源 | 唯一实例 | 含义 |
|---|---|---|
| Oracle 库 | `ykt/ykt@127.0.0.1:1521/orcl` | 全 Agent 共用一个库，并发 DDL/种子会撞 |
| 后端端口 | `8080` | 同时只能一个后端在跑 |
| 前端端口 | `3000`(被占顺延) | 同时只能一个 Vite |

**铁律**：子 Agent 只做"改代码 + 编译通过"，**不准 `start.ps1` 起服务、不准连库验证**。
起服务、整体验证、跑 SQL 一律由**主 Agent 串行收口**。

---

## 1. 模块纵切 + ownership（低冲突）

每个子 Agent 包干一条菜单的全栈：`entity + mapper + controller + vue + 该模块独有逻辑`。
各模块动各自的 controller/vue，天然不打架。顶层模块与目录对应：

| 模块 | 顶层菜单id | 后端 controller 前缀 | 前端目录 |
|---|---|---|---|
| 安全管理 | 1 | `/sys/*` | `views/sys/` |
| 系统管理(设置) | 2 | `/setup/*` | `views/setup/` |
| 主管部门 | 3 | `/dept/*` | `views/dept/` |
| 花名册 | 4 | `/roster`,`/dept/roster` | `views/roster/` |
| 集中支付 | 5 | `/pay/*` | `views/pay/` |
| 发放数据审核 | 6 | `/dept/audit` | `views/audit/` |
| 惠民报表 | 7 | `/report/*` | `views/report/` |

---

## 2. 公共文件 = 禁区，只许主 Agent 改

这些文件每个模块都要碰 → 并发改必冲突。**子 Agent 不许动**，需要加内容就在交付说明里列清单，主 Agent 串行合并：

- `frontend/src/router/index.js`（加路由）
- `frontend/src/api/system.js`（加 API）
- `frontend/src/layout/*`（菜单/布局）
- `backend/.../common/*`、`config/*`、`security/*`（基础设施）
- `start.ps1` / `stop.ps1` / `pom.xml` / `package.json`
- 任何数据库 schema / 种子 / 迁移脚本

---

## 3. SYS_MENU id 分段（防并发 insert 撞号）

子菜单 id 规则 = **父级顶层 id × 100 + 序号**，各模块在自己的百位段里取号：

| 模块 | 子菜单 id 段 | 已占用 |
|---|---|---|
| 安全管理 | 101–199 | 101–104 |
| 系统管理 | 201–299 | 201–205 |
| 主管部门 | 301–399 | 301–310 |
| 花名册 | 401–499 | 401–402 |
| 集中支付 | 501–599 | 501–503 |
| 发放数据审核 | 601–699 | 601 |
| 惠民报表 | 701–799 | 701–705 |

新增菜单：在本模块段内取**未占用**的最小号。`SYS_ROLE_MENU` 有 `ID` 主键(非空)，
插入用 `(SELECT NVL(MAX(id),0) FROM SYS_ROLE_MENU)+ROWNUM`。授权对齐同模块已有菜单的 role。

---

## 4. 数据库改动：单口、幂等、不碰源库

- 所有 DDL / 种子 / 迁移由**主 Agent 一个口子**走 `sqlplus`，串行执行。
- 写 SQL：`SET DEFINE OFF`，`.sql` 存 **UTF-8 无 BOM**，多行 DML 每条**一行**(SQL*Plus 续行以 `SET` 开头会被当 SET 命令)。Oracle 11g **无 `FETCH FIRST`**，用 `ROWNUM`。
- 改动幂等：先 `DELETE WHERE ...` 再 `INSERT`，可重跑。
- 本项目所有数据均为**虚构演示数据**，仅落本地 `ykt@orcl`。

---

## 5. 编译/验证命令（子 Agent 自检用，不起服务）

```bash
# 后端：只编译，不 run（PATH 默认 java 是 JDK8，必须显式 17）
JAVA_HOME='C:\Program Files\Java\jdk-17' \
  'C:\apache-maven-3.9.6\bin\mvn.cmd' -q -f backend/pom.xml compile

# 前端：构建校验（不 dev）
'C:\Program Files\nodejs\npm.cmd' --prefix frontend run build
```

主 Agent 整体验证：`./start.ps1`（起 8080+3000）→ login admin/admin123 → 走流程/QA。

---

## 6. worktree 集群流程（主 Agent 编排）

1. `writing-plans` 出模块拆分计划，明确每个子 Agent 的边界 + 禁区文件清单。
2. 每个子 Agent 用 `Agent(isolation:"worktree")` 起独立工作树，各写各模块、**编译通过**为交付标准。
3. 子 Agent 交付：改了哪些文件 + 需要主 Agent 在公共文件加什么(路由/API/菜单 id/SQL)。
4. 主 Agent 串行收口：合并 worktree → 改公共文件 → 跑 DB 变更 → `start.ps1` 整体验证。
5. 全绿后由主 Agent 统一 commit（提交信息末尾带 `Co-Authored-By: Claude ...`）。

---

## 7. 账号 / 坐标速查

- 登录：`admin / admin123`；前端 `http://localhost:3000/hfmp-ykt/`，后端 `/hfmp-ykt/api`。
- 工具链：JDK17 `C:\Program Files\Java\jdk-17`、Maven `C:\apache-maven-3.9.6`、Node `C:\Program Files\nodejs`。
- sqlplus：`<ORACLE_HOME>\BIN\sqlplus.exe`，`NLS_LANG=AMERICAN_AMERICA.AL32UTF8`。
- 业务约定散落在用户级 memory(`~/.claude/projects/D--ClaudeCode/memory/`)，主 Agent 派发前可摘要给子 Agent。
