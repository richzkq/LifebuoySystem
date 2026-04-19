package com.lifebuoysystem.entity;

import lombok.Data;

/**
 * @author zkq
 */

@Data
public class User {

    private Long id;
    private String username;
    private String password;
    private String role;
    private Integer status;
}