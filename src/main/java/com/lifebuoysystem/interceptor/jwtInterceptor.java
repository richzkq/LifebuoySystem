package com.lifebuoysystem.interceptor;

import com.lifebuoysystem.utils.jwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 令牌校验拦截器
 * 白名单由 webConfig.addInterceptors 中的 excludePathPatterns 统一管理
 *
 * @author ZKQ
 */
public class jwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String token = request.getHeader("token");

        if (token == null || token.isEmpty()) {
            throw new RuntimeException("未登录");
        }

        jwtUtils.verify(token);

        return true;
    }
}
