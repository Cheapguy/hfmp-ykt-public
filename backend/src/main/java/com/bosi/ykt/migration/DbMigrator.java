package com.bosi.ykt.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自建轻量迁移器（替代 Flyway——社区版已拒支 Oracle 11g）。目标与 Flyway 一致：
 * 版本追踪、幂等有序应用、已应用脚本被篡改可检测（checksum）。纯 JDBC，无外部框架。
 *
 * <p>约定：
 * <ul>
 *   <li>新迁移放 classpath {@code db/migration/} 下，命名 {@code V<版本号>__<描述>.sql}（版本号从 31 起）。</li>
 *   <li>老 {@code migrate_01~30_*.sql} 是 baseline 前历史（手工 sqlplus，不由本器管理）。
 *       首次运行只往 history 打一行 baseline（version={@code baselineVersion}），绝不重跑旧脚本。</li>
 *   <li>版本号 &le; baseline 的文件一律跳过（防误放旧号进目录）。</li>
 *   <li>已应用文件的内容不可再改：再次运行时比对 checksum，不一致直接中止（等价 Flyway validate）。</li>
 * </ul>
 *
 * <p>DDL 在 Oracle 无事务回滚，故每个迁移文件按语句顺序逐条执行；任一语句失败即中止后续，
 * 并把该版本以 success=0 记账，便于定位（修好后重跑不会重复已成功的版本）。
 */
@Slf4j
public class DbMigrator {

    /** history 表名（大写，避免 Oracle 引号小写标识符的别扭）。 */
    static final String HISTORY_TABLE = "SCHEMA_MIGRATION_HISTORY";

    /** 文件名 V<数字>__<描述>.sql */
    private static final Pattern FILE_RE = Pattern.compile("^V(\\d+)__(.+)\\.sql$", Pattern.CASE_INSENSITIVE);

    private final int baselineVersion;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public DbMigrator(int baselineVersion) {
        this.baselineVersion = baselineVersion;
    }

    /** 一条待应用迁移。 */
    private record Mig(int version, String description, String script, String checksum, String body) {}

    /**
     * 执行迁移。幂等：已应用版本跳过；无待应用时只确保 history 表 + baseline 行存在。
     *
     * @return 本次实际应用的版本数（0 表示已最新）
     */
    public int migrate(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        ensureHistoryTable(conn);
        ensureBaseline(conn);

        Map<Integer, String> applied = loadApplied(conn); // version -> checksum（success=1 的）
        List<Mig> all = scan();

        // 1) 校验：已应用且有 checksum 的文件，内容不得变更
        for (Mig m : all) {
            String old = applied.get(m.version());
            if (old != null && m.checksum() != null && !old.equals(m.checksum())) {
                throw new IllegalStateException(String.format(
                        "迁移 V%d (%s) 已应用但内容被改（checksum 不符：库=%s 文件=%s）。" +
                        "已应用脚本不可修改——请新建更高版本的迁移。",
                        m.version(), m.script(), old, m.checksum()));
            }
        }

        // 2) 待应用 = 版本 > baseline 且未成功应用过，按版本升序
        List<Mig> pending = new ArrayList<>();
        for (Mig m : all) {
            if (m.version() <= baselineVersion) continue;      // baseline 前的旧号一律跳过
            if (applied.containsKey(m.version())) continue;    // 已成功应用
            pending.add(m);
        }
        pending.sort((a, b) -> Integer.compare(a.version(), b.version()));

        if (pending.isEmpty()) {
            log.info("[migrate] 无待应用迁移，schema 已是最新（baseline={}）。", baselineVersion);
            return 0;
        }

        log.info("[migrate] 待应用 {} 个迁移：{}", pending.size(),
                pending.stream().map(m -> "V" + m.version()).toList());

        for (Mig m : pending) {
            applyOne(conn, m);
        }
        log.info("[migrate] 完成，共应用 {} 个迁移。", pending.size());
        return pending.size();
    }

    /** 应用单个迁移文件：逐语句执行 + 记账，全成功才提交该版本。 */
    private void applyOne(Connection conn, Mig m) throws SQLException {
        log.info("[migrate] 应用 V{} {} ...", m.version(), m.description());
        long t0 = System.currentTimeMillis();
        List<String> stmts = splitStatements(m.body());
        try (Statement st = conn.createStatement()) {
            for (String s : stmts) {
                st.execute(s);
            }
            long ms = System.currentTimeMillis() - t0;
            record(conn, m, ms, true);
            conn.commit();
            log.info("[migrate] V{} 成功（{} 条语句，{}ms）。", m.version(), stmts.size(), ms);
        } catch (SQLException e) {
            // DDL 已自动提交无法整体回滚；把失败版本记为 success=0 便于定位，再抛出中止后续
            try {
                record(conn, m, System.currentTimeMillis() - t0, false);
                conn.commit();
            } catch (SQLException ignore) {
                conn.rollback();
            }
            throw new SQLException("迁移 V" + m.version() + " (" + m.script() + ") 执行失败：" + e.getMessage(), e);
        }
    }

    // ---- history 表 ----

    private void ensureHistoryTable(Connection conn) throws SQLException {
        if (tableExists(conn, HISTORY_TABLE)) return;
        log.info("[migrate] 创建 history 表 {}", HISTORY_TABLE);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + HISTORY_TABLE + " (" +
                    "VERSION       NUMBER(10)    NOT NULL, " +
                    "DESCRIPTION   VARCHAR2(200), " +
                    "SCRIPT        VARCHAR2(200), " +
                    "CHECKSUM      VARCHAR2(64), " +
                    "INSTALLED_BY  VARCHAR2(50), " +
                    "INSTALLED_ON  DATE DEFAULT SYSDATE NOT NULL, " +
                    "EXECUTION_MS  NUMBER(10), " +
                    "SUCCESS       NUMBER(1)     DEFAULT 1 NOT NULL, " +
                    "CONSTRAINT PK_" + HISTORY_TABLE + " PRIMARY KEY (VERSION))");
        }
        conn.commit();
    }

    /** history 为空时打 baseline 行，标记 01~30 视为已应用。 */
    private void ensureBaseline(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + HISTORY_TABLE);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }
        log.info("[migrate] 打 baseline 行 version={}（01~30 视为 baseline 前手工迁移）", baselineVersion);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + HISTORY_TABLE +
                " (VERSION, DESCRIPTION, SCRIPT, CHECKSUM, INSTALLED_BY, EXECUTION_MS, SUCCESS) " +
                " VALUES (?, ?, NULL, NULL, ?, 0, 1)")) {
            ps.setInt(1, baselineVersion);
            ps.setString(2, "<< baseline: pre-ledger sqlplus migrations 01-" + baselineVersion + " >>");
            ps.setString(3, currentUser(conn));
            ps.executeUpdate();
        }
        conn.commit();
    }

    private Map<Integer, String> loadApplied(Connection conn) throws SQLException {
        Map<Integer, String> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT VERSION, CHECKSUM FROM " + HISTORY_TABLE + " WHERE SUCCESS = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getInt("VERSION"), rs.getString("CHECKSUM"));
            }
        }
        return map;
    }

    private void record(Connection conn, Mig m, long ms, boolean success) throws SQLException {
        // 同版本可能先失败(success=0)后重跑成功：用 MERGE 覆盖，避免主键冲突
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO " + HISTORY_TABLE + " t USING (SELECT ? VERSION FROM DUAL) s " +
                "ON (t.VERSION = s.VERSION) " +
                "WHEN MATCHED THEN UPDATE SET DESCRIPTION=?, SCRIPT=?, CHECKSUM=?, INSTALLED_BY=?, " +
                "  INSTALLED_ON=SYSDATE, EXECUTION_MS=?, SUCCESS=? " +
                "WHEN NOT MATCHED THEN INSERT (VERSION, DESCRIPTION, SCRIPT, CHECKSUM, INSTALLED_BY, EXECUTION_MS, SUCCESS) " +
                "  VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            String by = currentUser(conn);
            int i = 1;
            ps.setInt(i++, m.version());
            // update set
            ps.setString(i++, m.description());
            ps.setString(i++, m.script());
            ps.setString(i++, m.checksum());
            ps.setString(i++, by);
            ps.setLong(i++, ms);
            ps.setInt(i++, success ? 1 : 0);
            // insert values
            ps.setInt(i++, m.version());
            ps.setString(i++, m.description());
            ps.setString(i++, m.script());
            ps.setString(i++, m.checksum());
            ps.setString(i++, by);
            ps.setLong(i++, ms);
            ps.setInt(i, success ? 1 : 0);
            ps.executeUpdate();
        }
    }

    // ---- 扫描 / 解析 ----

    private List<Mig> scan() {
        List<Mig> list = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:db/migration/V*.sql");
        } catch (Exception e) {
            log.warn("[migrate] 扫描 db/migration 失败：{}", e.getMessage());
            return list;
        }
        for (Resource r : resources) {
            String name = r.getFilename();
            if (name == null) continue;
            Matcher mt = FILE_RE.matcher(name);
            if (!mt.matches()) continue;
            int version = Integer.parseInt(mt.group(1));
            String desc = mt.group(2).replace('_', ' ');
            String body;
            try (var in = r.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            } catch (Exception e) {
                throw new RuntimeException("读取迁移文件失败：" + name, e);
            }
            list.add(new Mig(version, desc, name, sha256(body), body));
        }
        return list;
    }

    /**
     * 拆分语句：普通语句以行尾 {@code ;} 结束；PL/SQL 匿名块（DECLARE/BEGIN 开头或 CREATE OR REPLACE
     * PROCEDURE/FUNCTION/TRIGGER/PACKAGE/TYPE）整块保留，以单独一行 {@code /} 结束。
     * 与老 sqlplus 脚本口径一致（见记忆 zjc ZwjSetup PL/SQL 切分）。
     */
    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean inPlsql = false;
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            // "空"= buffer 里只有空白/整行注释（否则前置 -- 注释会挡住 PL/SQL 块识别）
            boolean bufBlank = noRealContent(buf);

            if (!inPlsql && bufBlank && startsPlsql(trimmed)) {
                inPlsql = true;
            }

            if (inPlsql) {
                if (trimmed.equals("/")) {                 // PL/SQL 块结束
                    flush(out, buf);
                    inPlsql = false;
                } else {
                    buf.append(line).append('\n');
                }
                continue;
            }

            // 普通语句
            buf.append(line).append('\n');
            // 剥掉行尾 -- 注释再判断是否以 ; 结尾（避免注释里的分号误终结）
            String noComment = stripTrailingLineComment(trimmed);
            if (noComment.endsWith(";")) {
                // 去掉末尾分号
                int idx = buf.lastIndexOf(";");
                buf.deleteCharAt(idx);
                flush(out, buf);
            }
        }
        flush(out, buf); // 收尾：容忍最后一条无终结符
        return out;
    }

    /** buffer 是否无实质内容（只有空行或整行 {@code --} 注释）——用于 PL/SQL 块起点判定。 */
    private static boolean noRealContent(CharSequence sb) {
        for (String l : sb.toString().split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("--")) return false;
        }
        return true;
    }

    private static boolean startsPlsql(String t) {
        String u = t.toUpperCase();
        return u.startsWith("DECLARE") || u.startsWith("BEGIN")
                || u.matches("^CREATE\\s+(OR\\s+REPLACE\\s+)?(PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE)\\b.*");
    }

    private static String stripTrailingLineComment(String line) {
        int i = line.indexOf("--");
        return (i >= 0 ? line.substring(0, i) : line).trim();
    }

    private static void flush(List<String> out, StringBuilder buf) {
        String s = buf.toString().trim();
        buf.setLength(0);
        if (s.isEmpty()) return;
        // 整块只有注释则跳过
        boolean onlyComments = true;
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("--")) { onlyComments = false; break; }
        }
        if (!onlyComments) out.add(s);
    }

    // ---- 工具 ----

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static String currentUser(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            return "unknown";
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
