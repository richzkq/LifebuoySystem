package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.entity.User;
import com.lifebuoysystem.mapper.userMapper;
import com.lifebuoysystem.service.UserService;
import com.lifebuoysystem.utils.jwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author ZKQ
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final userMapper userMapper;

    @Override
    public String login(String username, String password) {
        User dbUser = userMapper.findByUsername(username);
        if (dbUser == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!dbUser.getPassword().equals(password)) {
            throw new RuntimeException("密码错误");
        }
        return jwtUtils.createToken(dbUser.getId(), dbUser.getUsername());
    }
}
