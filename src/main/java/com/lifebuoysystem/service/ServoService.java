package com.lifebuoysystem.service;

import java.util.Map;

/**
 * 舵机控制服务 — 连续多帧溺水检测 + MQTT 推送给 ESP8266
 *
 * @author ZKQ
 */
public interface ServoService {

    /**
     * 由 DeviceServiceImpl 每帧调用
     * @param deviceId      设备 ID
     * @param drowningCount 当前帧溺水人数 (null 表示无数据)
     */
    void onFrameProcessed(String deviceId, Integer drowningCount);

    /**
     * 手动触发舵机 (用于测试)
     */
    void manualTrigger(String deviceId);

    /**
     * 重置指定设备的连续计数和释放状态
     */
    void reset(String deviceId);

    /**
     * 获取指定设备的舵机状态
     */
    Map<String, Object> getStatus(String deviceId);

    /**
     * 获取所有设备的舵机状态
     */
    Map<String, Object> getAllStatus();
}
