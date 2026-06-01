package com.lifebuoysystem.service;

/**
 * @author ZKQ
 */
public interface UserService {

    /**
     * 用户登录，校验用户名密码，返回JWT令牌
     * @throws RuntimeException 用户名不存在或密码错误
     */
    String login(String username, String password);
}
