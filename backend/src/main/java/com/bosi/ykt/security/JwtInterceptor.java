package com.bosi.ykt.security;

import com.bosi.ykt.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String token = req.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        if (token == null || token.isBlank()) token = req.getHeader("X-Token");
        if (token == null || token.isBlank()) throw new BizException(401, "未登录");
        try {
            Claims c = jwtUtil.parse(token);
            UserContext.set(new UserContext(
                    c.get("uid", Long.class),
                    c.get("uname", String.class),
                    c.get("tid", Long.class)
            ));
        } catch (Exception e) {
            throw new BizException(401, "登录已过期");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        UserContext.clear();
    }
}
