# 惠民惠农财政补贴"一卡通"管理模块（脚手架）

> ⚠️ **脱敏演示副本**：本仓库为公开演示版本，所有机构名、地名、人名、身份证号、银行卡号、行政区划码**均为虚构**，与任何真实单位或个人无关；数据仅用于展示系统功能。

技术栈：**Spring Boot 3 + MyBatis-Plus + Druid + Oracle 11g / Vue3 + Vite + Element-Plus**。

## 目录
```
hfmp-ykt/
├── backend/   Spring Boot 3 后端（com.bosi.ykt）
│   └── src/main/resources/db/{schema.sql, seed.sql}
└── frontend/  Vue3 + Vite 前端
```

## 快速启动

### 1. 数据库（Oracle 11g）
```sql
-- 用业务账号依次执行
@backend/src/main/resources/db/schema.sql
@backend/src/main/resources/db/seed.sql
```

### 2. 后端
改连接串（环境变量或 application.yml）：
```
YKT_DB_URL=jdbc:oracle:thin:@主机:1521/服务名
YKT_DB_USER=ykt
YKT_DB_PWD=ykt
```
```bash
cd backend
mvn spring-boot:run
# http://localhost:8080/hfmp-ykt/api/health
```

### 3. 前端
```bash
cd frontend
npm install
npm run dev
# http://localhost:3000/hfmp-ykt/
```

## 默认账号
| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 系统管理员（全菜单）|
| town_op | 123456 | 乡镇经办 |
| town_audit | 123456 | 乡镇审核 |
| dept_op | 123456 | 部门经办 |
| dept_audit | 123456 | 部门审核 |

> 密码为明文，登录时 BCrypt 校验失败会回退明文比对。生产环境请改用 BCrypt 哈希并走环境变量。

## 业务闭环
```
补贴项目维护(送审→审核→纳入/挂接)
  → 补贴批次维护(按部门新增→批次下达)
    → 花名册填报(批量填报/引用对象库)
      → 多级审核(乡镇经办→乡镇审核→部门经办→部门审核, 校验姓名/身份证/社保卡/村组)
        → 批次发送一体化
          → 发起支付申请 → 签章送审 → 银行支付
            → 批次已发放 / 退款 / 退回 / 更正
```

## 模块映射
| 前端路由 | 后端前缀 | 说明 |
|---|---|---|
| /setup/bank | /setup/bank | 开户银行设置 |
| /setup/village | /setup/village | 村组维护 |
| /setup/beneficiary | /setup/beneficiary | 补贴对象维护 |
| /dept/project | /dept/project | 补贴项目维护/审核/纳入挂接 |
| /dept/notice | /dept/notice | 通知公告管理 |
| /dept/batch | /dept/batch | 补贴批次维护 |
| /roster/fill | /roster | 花名册维护 |
| /pay/quota | /pay/quota | 项目额度(指标)挂接 |
| /pay/apply | /pay/apply | 支付申请/签章送审 |

## 通用底盘
`R<T>` 统一响应体、`BaseCrudController` CRUD 工厂、`BaseEntity` 审计字段、JWT 三件套
(`JwtUtil`/`JwtInterceptor`/`UserContext`) + `AuthorizationInterceptor`(菜单级授权)、
`MybatisMetaHandler` 自动填充、`JacksonConfig`(Long→String 防雪花精度丢失)、
前端 `makeCrud` 工厂 + `CrudTable` 通用表格组件。

## 功能状态（原「待补」清单均已落地）
- ✅ 附件上传/下载：公告附件 + 项目政策文件附件（uuid 落盘防路径穿越，`/files/preview` 免登录下载）
- ✅ Excel 导入/导出：花名册批量导入、报表/花名册/查询导出（EasyExcel）
- ✅ 退款/退回/更正发放明细处理：更正发放重构闭环（含二次/三次发放，源明细标记防重复重构）
- ✅ 项目额度挂接与扣减：银行代发逐笔标记成败、失败计退回金额（资金守恒：下达 = 已付 + 退回）
- ✅ 县域数据隔离：`SYS_ROLE.DATA_SCOPE` + `DataScopeResolver`，读取面 + 写接口 `assertScope` 兜底全闭环
- ✅ 安全硬化：菜单级授权拦截器（`HandlerMethod` 精确匹配）、登录失败锁定/渐进验证码、JWT 密钥启动校验、越权/IDOR `assertReadable` 兜底
- ✅ 空库可重建：`db/schema.sql`（按活库 DDL 重建，含全部表/列）+ `db/seed.sql`（脱敏种子）

> 仍属演示简化：项目编码在终审后按「区划 + 时间序列」模拟生成，生产环境由编码规则平台统一下发。
