package com.bosi.ykt.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 迁移触发器：<b>默认关闭</b>，仅当传入 {@code ykt.migrate=true} 时执行，跑完即退出 JVM。
 * 正常应用启动完全不受影响（flag 缺省 false 时直接 return）——迁移是独立的运维动作，
 * 坏迁移不会 brick 服务。复用应用已配好的 Druid/Oracle 数据源与 env 凭据，无需另配连接。
 *
 * <p>部署流水线手动执行（推荐随 fat jar，Web 关掉不占端口）：
 * <pre>
 *   java -jar ykt-backend-1.0.0.jar --ykt.migrate=true --spring.main.web-application-type=none
 * </pre>
 * 生产覆盖连接用环境变量 {@code YKT_DB_URL/YKT_DB_USER/YKT_DB_PWD}（与应用同一套）。
 * 退出码 0=成功（含"已最新"），1=失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final Environment env;
    private final ConfigurableApplicationContext ctx;

    @Override
    public void run(ApplicationArguments args) {
        boolean go = env.getProperty("ykt.migrate", Boolean.class, false) || args.containsOption("ykt.migrate");
        if (!go) return; // 正常启动路径：零影响

        int baseline = env.getProperty("ykt.migrate.baseline", Integer.class, 30);
        int code;
        try (Connection conn = dataSource.getConnection()) {
            log.info("[migrate] 手动迁移开始（baseline={}）", baseline);
            new DbMigrator(baseline).migrate(conn);
            code = 0;
        } catch (Exception e) {
            log.error("[migrate] 迁移失败，已中止：{}", e.getMessage(), e);
            code = 1;
        }
        final int exitCode = code;
        System.exit(SpringApplication.exit(ctx, () -> exitCode));
    }
}
