package com.aitome.access;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AccessWebConfig implements WebMvcConfigurer {
    private final PermissionInterceptor permissions;

    public AccessWebConfig(PermissionInterceptor permissions) { this.permissions = permissions; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissions).addPathPatterns("/api/**");
    }
}
