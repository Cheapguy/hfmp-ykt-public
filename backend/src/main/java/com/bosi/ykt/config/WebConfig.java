package com.bosi.ykt.config;

import com.bosi.ykt.security.AuthorizationInterceptor;
import com.bosi.ykt.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    /** 允许跨域的前端来源，逗号分隔。生产同源部署其实不需要 CORS，默认只放本地开发端口。 */
    @Value("${ykt.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 原先是 allowedOriginPatterns("*") + allowCredentials(true)：任意站点都能带着凭据
        // 打这套接口。本项目认证走 Authorization 头、不依赖跨域 Cookie，所以直接关掉 credentials，
        // 来源也收成白名单（同源部署留空即可，压根不会走到 CORS）。
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split("\\s*,\\s*"))
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String[] excludes = {
                "/auth/login",
                "/auth/captcha",
                "/health",
                "/error",
                "/druid/**"
                // /files/preview/** 曾在此免登录放行：附件名是 uuid，但只要泄漏或猜到一次
                // 就能长期匿名下载政策文件/通知附件，且没有任何审计。前端已改为 axios 取 blob
                // （见 utils/download.js），能带上 Authorization 头，这里不再放行。
        };
        // 认证在前（填充 UserContext），授权在后（基于 UserContext 校验菜单权限）
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludes);
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludes);
    }
}
