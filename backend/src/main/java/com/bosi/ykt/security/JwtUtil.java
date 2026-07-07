package com.bosi.ykt.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${ykt.jwt.secret}")
    private String secret;

    @Value("${ykt.jwt.expire-hours}")
    private long expireHours;

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
