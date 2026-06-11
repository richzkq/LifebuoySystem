package com.lifebuoysystem.mapper;


import com.lifebuoysystem.entity.AlarmRecord;
import org.apache.ibatis.annotations.*;

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

    /** 确认报警完成 */
    @Update("UPDATE alarm_record SET status = 'COMPLETED' WHERE id = #{id}")
    int acknowledge(@Param("id") Long id);

    /** 查询某设备是否有未确认的溺水报警 */
    @Select("SELECT COUNT(*) FROM alarm_record WHERE device_id = #{deviceId} AND alarm_type = 'Drowning' AND status = 'PENDING'")
    int countPending(@Param("deviceId") String deviceId);

    /** 压力传感器触发 → 自动确认该设备所有未处理报警 */
    @Update("UPDATE alarm_record SET status = 'COMPLETED' WHERE device_id = #{deviceId} AND alarm_type = 'Drowning' AND status = 'PENDING'")
    int autoCompleteByPressure(@Param("deviceId") String deviceId);
}
