package com.Cra2iTeT.config;

import com.Cra2iTeT.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

//@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    public WebMvcConfiguration(LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                // 1： 拦截器注册
                .addInterceptor(loginInterceptor)
                // 2: 给拦截器配置并且定义规则
                .excludePathPatterns("/user/login", "/user/save")
                .addPathPatterns("/**");
    }
}