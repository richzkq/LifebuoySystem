package com.lifebuoysystem.utils;


import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Date;

/**
 * @author ZKQ
 */

public class jwtUtils {
    private static final String SECRET = "device-secret-key";

    public static String createToken(Long userId,String username){

        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withExpiresAt(new Date(System.currentTimeMillis() + 7*24*60*60*1000))
                .sign(Algorithm.HMAC256(SECRET));
    }

    public static void verify(String token){
        JWT.require(Algorithm.HMAC256(SECRET)).build().verify(token);
    }
}
