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

        // 放行接口
        if (path.startsWith("/device/upload") ||
                path.startsWith("/login") ||
                path.startsWith("/user/login") ||
                path.startsWith("/uploads/") ||
                path.startsWith("/ws") ||
                path.startsWith("/api/alarm")) {

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