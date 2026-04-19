package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.entity.User;
import com.lifebuoysystem.mapper.userMapper;
import com.lifebuoysystem.utils.jwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

        String token = jwtUtils.createToken(dbUser.getId(),dbUser.getUsername());
        return result.success(token);
    }

    @GetMapping("/info")
    public result info(){
        return result.success("登录成功.......");
    }

}
