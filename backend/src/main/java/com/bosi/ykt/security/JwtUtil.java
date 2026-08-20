package com.bosi.ykt.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    /** 与 application.yml 中的兜底占位符一致；出现即视为「未配置」。 */
    private static final String PLACEHOLDER = "dev_only_placeholder_set_YKT_JWT_SECRET_in_prod";

    @Value("${ykt.jwt.secret}")
    private String secret;

    @Value("${ykt.jwt.expire-hours}")
    private long expireHours;

    private final Environment env;

    public JwtUtil(Environment env) { this.env = env; }

    /**
     * 启动即校验密钥强度：占位符/空/过短(HS256 需 ≥32 字节)时——
     *  - 显式 dev profile：放行但打醒目 WARN（本地起服务不必配环境变量）；
     *  - 其余一律拒绝启动（含不带 profile 的默认启动）。
     *
     * <p>此前的判据是「只有 prod 才拒」，但部署脚本漏写 {@code --spring.profiles.active=prod}
     * 是最常见的事故形态，等于生产照样跑在公开占位符上——占位符是仓库里的明文，
     * 谁都能拿它签一个 uid=1 的 token 当管理员。默认安全的判据必须反过来：不是显式开发就拒。
     */
    @PostConstruct
    void validateSecret() {
        boolean weak = secret == null || secret.isBlank()
                || secret.equals(PLACEHOLDER)
                || secret.getBytes(StandardCharsets.UTF_8).length < 32;
        if (!weak) return;
        boolean dev = false;
        for (String p : env.getActiveProfiles()) if ("dev".equalsIgnoreCase(p)) dev = true;
        if (!dev) throw new IllegalStateException(
                "JWT 密钥未配置或过弱（须 ≥32 字节且非占位符），拒绝启动。"
                + "请设置环境变量 YKT_JWT_SECRET；本地开发可加 --spring.profiles.active=dev 放行");
        log.warn("[安全] JWT 密钥为占位/弱值，仅限 dev；生产部署前必须设置强随机 YKT_JWT_SECRET");
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long userId, String username, Long tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("uname", username);
        claims.put("tid", tenantId);
        Date now = new Date();
        Date exp = new Date(now.getTime() + expireHours * 3600_000L);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
