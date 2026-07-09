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
     *  - 生产 profile(prod)：直接拒绝启动（否则可凭已知占位符伪造管理员 token）；
     *  - 非生产：放行但打醒目 WARN。
     */
    @PostConstruct
    void validateSecret() {
        boolean weak = secret == null || secret.isBlank()
                || secret.equals(PLACEHOLDER)
                || secret.getBytes(StandardCharsets.UTF_8).length < 32;
        if (!weak) return;
        boolean prod = false;
        for (String p : env.getActiveProfiles()) if ("prod".equalsIgnoreCase(p)) prod = true;
        if (prod) throw new IllegalStateException(
                "生产环境 YKT_JWT_SECRET 未配置或过弱（须 ≥32 字节且非占位符），拒绝启动");
        log.warn("[安全] JWT 密钥为占位/弱值，仅限开发；生产部署前必须设置强随机 YKT_JWT_SECRET");
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
