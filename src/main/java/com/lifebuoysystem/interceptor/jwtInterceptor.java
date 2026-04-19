package com.lifebuoysystem.interceptor;


import com.lifebuoysystem.utils.jwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author ZKQ
 */

public class jwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        String token = request.getHeader("token");

        if(token == null || token.isEmpty()){
            throw new RuntimeException("未登录");
        }

        jwtUtils.verify(token);

        return true;
    }
}
