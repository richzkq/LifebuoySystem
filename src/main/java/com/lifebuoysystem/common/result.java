package com.lifebuoysystem.common;

import lombok.Data;

/**
 * @author zkq
 */

@Data
public class result<T> {

    private Integer code;
    private String msg;
    private T data;

    public static result success(Object data){
        result r = new result();
        r.code = 200;
        r.msg = "成功";
        r.data = data;
        return r;
    }

    public static result error(String msg){
        result r = new result();
        r.code = 500;
        r.msg = msg;
        return r;
    }
}