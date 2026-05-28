package com.lifebuoysystem.config;

import com.lifebuoysystem.interceptor.jwtInterceptor;
import com.lifebuoysystem.interceptor.jwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class webConfig implements WebMvcConfigurer {

    /**
     * 跨域配置
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {

        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * JWT 拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(new jwtInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/login/**",
                        "/device/upload",
                        "/ws/**",
                        "/uploads/**",
                        "/api/alarm/**",
                        "/error",
                        "/device/frame",       // 新增:接收图
                        "/device/snapshot/**", // 新增:单张图
                        "/device/stream/**" // 新增:MJPEG 流

                );
    }

    /**
     * 暴露 uploads 静态资源目录
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}