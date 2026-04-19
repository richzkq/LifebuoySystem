package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.entity.User;
import com.lifebuoysystem.mapper.userMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author zkq
 */

@RestController
@RequestMapping("/user")
public class userController {

    @Autowired
    private userMapper userMapper;

    @PostMapping("/login")
    public result login(@RequestBody User user){

        User dbUser = userMapper.findByUsername(user.getUsername());

        if(dbUser == null){
            return result.error("用户不存在");
        }

        if(!dbUser.getPassword().equals(user.getPassword())){
            return result.error("密码错误");
        }

        return result.success(dbUser);
    }
}
