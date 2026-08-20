package com.bosi.ykt.security;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bosi.ykt.entity.SysUser;
import com.bosi.ykt.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 明文口令一次性升级为 bcrypt。
 *
 * <p>历史上 {@code AuthController.passwordMatches} 对不以 {@code $2} 开头的 stored 值走明文相等比对
 * （脚手架种子留下的兼容分支），导致库里存量账号的口令是可直读的明文。删掉那条分支之前必须先把
 * 存量刷成 bcrypt，否则所有老账号当场登不进来。
 *
 * <p>幂等：只挑 PASSWORD 不以 {@code $2} 开头的行；刷完一次后续启动扫到 0 行，直接返回。
 * 用 UpdateWrapper 只写 PASSWORD 一列，避免 MyBatis-Plus 自动填充把 UPDATE_BY 写成 null。
 */
@Component
// 排在 MigrationRunner(@Order(10)) 之后：表结构先就位再刷数据。
// 顺序不是可有可无——空库 + --ykt.migrate=true 的路径上，若先跑口令升级会撞「表不存在」
// 而 warn 跳过，等迁移把表建好 JVM 已经退出，这一轮谁也没刷成。
// 两个 runner 必须都显式标 Order：不标 = LOWEST_PRECEDENCE，反而跑在标了的后面。
@Order(20)
@RequiredArgsConstructor
public class PasswordUpgradeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordUpgradeRunner.class);

    private final SysUserMapper userMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<SysUser> stale;
        try {
            stale = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .isNotNull(SysUser::getPassword)
                    .notLikeRight(SysUser::getPassword, "$2"));
        } catch (Exception e) {
            // 空库首启等场景 SYS_USER 尚不存在——不该因此挡住启动
            log.warn("[安全] 明文口令扫描跳过：{}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) return;

        int n = 0;
        for (SysUser u : stale) {
            String raw = u.getPassword();
            if (raw == null || raw.isBlank()) continue;
            userMapper.update(null, new UpdateWrapper<SysUser>()
                    .eq("ID", u.getId())
                    .set("PASSWORD", BCrypt.hashpw(raw, BCrypt.gensalt())));
            n++;
        }
        log.warn("[安全] 已将 {} 个账号的明文口令升级为 bcrypt；原口令不变，用户无需改密", n);
    }
}
