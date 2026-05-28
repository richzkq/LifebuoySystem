package com.lifebuoysystem.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlarmRecord {
    private Long id;
    private String deviceId;
    private String alarmType;
    private String status;
    private LocalDateTime createTime;
}