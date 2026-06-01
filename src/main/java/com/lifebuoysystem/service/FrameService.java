package com.lifebuoysystem.service;

/**
 * @author ZKQ
 */
public interface FrameService {

    /**
     * 存储设备最新一帧图像
     */
    void storeFrame(String deviceId, byte[] data);

    /**
     * 获取设备最新一帧图像（可能为null）
     */
    byte[] getSnapshot(String deviceId);
}
