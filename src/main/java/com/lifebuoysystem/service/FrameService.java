package com.lifebuoysystem.service;

/**
 * @author ZKQ
 */
public interface FrameService {

    /**
     * 存储设备最新一帧图像，同时唤醒等待的 MJPEG 流
     */
    void storeFrame(String deviceId, byte[] data);

    /**
     * 获取设备最新一帧图像（可能为null）
     */
    byte[] getSnapshot(String deviceId);

    /**
     * 阻塞等待新帧到达（有 notify 唤醒，不是轮询）
     * @param lastFrame 上一次推送的帧引用，用于去重
     * @param timeoutMs 超时时间（毫秒），超时返回 null
     * @return 新帧数据，超时返回 null
     */
    byte[] waitForNewFrame(String deviceId, byte[] lastFrame, long timeoutMs);
}
