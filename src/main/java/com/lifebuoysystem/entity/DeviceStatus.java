package com.lifebuoysystem.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DeviceStatus {

    private String deviceId;

    private Integer frameNo;

    // 溺水人数
    private Integer drowningCount;

    // 呼救数量
    private Integer heardCall;

    // 压力状态
    private Integer pressure;

    // 综合报警
    private Integer alarm;

    // 上传时间
    private LocalDateTime uploadTime;

    // 坐标
    private List<TargetInfo> targets;

    // 图片路径
    private String imageUrl;
}