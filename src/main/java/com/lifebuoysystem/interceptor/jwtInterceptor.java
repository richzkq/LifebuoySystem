package com.lifebuoysystem.interceptor;

import com.lifebuoysystem.utils.jwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class jwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String path = request.getRequestURI();
        System.out.println("当前请求路径: " + path);

        // 放行接口
        if (path.startsWith("/device/upload") ||
                path.startsWith("/device/latest") ||
                path.startsWith("/login") ||
                path.startsWith("/user/login") ||
                path.startsWith("/uploads/") ||
                path.startsWith("/ws")) {

            return true;
        }

        String token = request.getHeader("token");

        if (token == null || token.isEmpty()) {
            throw new RuntimeException("未登录");
        }

        jwtUtils.verify(token);

        return true;
    }
}