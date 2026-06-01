package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.entity.User;
import com.lifebuoysystem.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @author zkq
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class userController {

    private final UserService userService;

    @PostMapping("/login")
    public result login(@RequestBody User user) {
        try {
            String token = userService.login(user.getUsername(), user.getPassword());
            return result.success(token);
        } catch (RuntimeException e) {
            return result.error(e.getMessage());
        }
    }

    @GetMapping("/info")
    public result info() {
        return result.success("登录成功.......");
    }
}
