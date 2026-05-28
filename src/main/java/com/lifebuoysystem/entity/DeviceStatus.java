package com.lifebuoysystem.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DeviceStatus {

    private String deviceId;
    private Integer frameNo;
    private Integer drowningCount;     // 溺水人数
    private Integer personCount;       // 总人数
    private Integer callForHelp;       // 呼救声
    private Integer pressure;          // 压力状态(1=已救到人)
    private Integer alarm;             // 综合报警
    private LocalDateTime uploadTime;
    private List<TargetInfo> targets;
    private String imageUrl;
}