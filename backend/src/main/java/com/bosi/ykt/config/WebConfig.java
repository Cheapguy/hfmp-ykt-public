package com.bosi.ykt.config;

import com.bosi.ykt.security.AuthorizationInterceptor;
import com.bosi.ykt.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String[] excludes = {
                "/auth/login",
                "/auth/captcha",
                "/health",
                "/error",
                "/druid/**",
                "/files/preview/**"
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
