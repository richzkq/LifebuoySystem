package com.lifebuoysystem.entity;


import lombok.Data;

import java.util.List;

/**
 * @author ZKQ
 */

@Data
public class FramePayload {

    private String  deviceId;
    private Integer frameNo;
    private Integer detectCount;
    private String  imageBase64;   // 图片以 Base64 字符串推送，Vue 直接渲染
    private String imageUrl;
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