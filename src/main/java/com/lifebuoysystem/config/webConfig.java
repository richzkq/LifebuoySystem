package com.lifebuoysystem.config;


import com.lifebuoysystem.interceptor.jwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author ZKQ
 */

@Configuration
public class webConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(new jwtInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login"
                );
    }
}
