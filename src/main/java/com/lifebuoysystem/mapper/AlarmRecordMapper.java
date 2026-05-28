package com.lifebuoysystem.mapper;


import com.lifebuoysystem.entity.AlarmRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    @Insert("""
    INSERT INTO alarm_record(device_id, alarm_type, status)
    VALUES(#{deviceId}, #{alarmType}, #{status})
""")
    void insert(@Param("deviceId") String deviceId,
                @Param("alarmType") String alarmType,
                @Param("status") String status);
}
