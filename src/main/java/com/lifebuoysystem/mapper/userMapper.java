package com.lifebuoysystem.mapper;

import com.lifebuoysystem.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author zkq
 */


@Mapper
public interface userMapper {

    @Select("select * from user where username = #{username}")
    User findByUsername(String username);
}