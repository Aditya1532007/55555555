package com.seekora.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {
    private final RequireLoginInterceptor requireLogin;

    public WebSecurityConfig(RequireLoginInterceptor requireLogin) { this.requireLogin = requireLogin; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requireLogin).addPathPatterns("/api/**");
    }
}
