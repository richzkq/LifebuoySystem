package com.lifebuoysystem.mapper;


import com.lifebuoysystem.entity.AlarmRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author ZKQ
 */

@Mapper
public interface AlarmRecordMapper {

    @Select("""
        SELECT *
        FROM alarm_record
        ORDER BY create_time DESC
        LIMIT 20
    """)
    List<AlarmRecord> list();
}
