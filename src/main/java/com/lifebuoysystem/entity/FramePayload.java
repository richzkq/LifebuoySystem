package com.lifebuoysystem.entity;


import lombok.Data;

import java.util.List;

/**
 * @author ZKQ
 */

@Data
public class FramePayload {
    // ========= 基础信息 =========
    private String deviceId;
    private Integer frameNo;

    // ========= 新版状态字段 =========
    private Integer drowningCount;   // 溺水人数
    private Integer personCount;     // 不在水中的人数
    private Integer callForHelp;     // 呼救声
    private Integer pressure;        // 是否救到人
    private Integer alarm;           // 综合报警状态

    // ========= 图片 =========
    private String imageBase64;
    private String imageUrl;

    // ========= 目标列表 =========
    private List<Target> targets;

    @Data
    public static class Target {

        private Integer index;

        private String label;

        private Float score;

        // 中心点
        private Integer centerX;
        private Integer centerY;
    }

}
