package com.lifebuoysystem.entity;


import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author ZKQ
 */

@Data
public class AlarmRecord {

    private Long id;

    private String deviceId;

    private String alarmType;

    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}