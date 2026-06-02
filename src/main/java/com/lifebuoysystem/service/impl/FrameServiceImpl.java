package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.service.FrameService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧缓存服务 — 带 wait/notify 机制
 * <p>
 * 每帧存储后 notify 唤醒 MJPEG 流，消除轮询延迟
 *
 * @author ZKQ
 */
@Service
public class FrameServiceImpl implements FrameService {

    private final Map<String, byte[]> latestFrames = new ConcurrentHashMap<>();
    /** 每个设备的帧通知锁 */
    private final Map<String, Object> frameLocks = new ConcurrentHashMap<>();

    @Override
    public void storeFrame(String deviceId, byte[] data) {
        latestFrames.put(deviceId, data);
        // 唤醒等待该设备帧的 MJPEG 流
        Object lock = frameLocks.get(deviceId);
        if (lock != null) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    @Override
    public byte[] getSnapshot(String deviceId) {
        return latestFrames.get(deviceId);
    }

    @Override
    public byte[] waitForNewFrame(String deviceId, byte[] lastFrame, long timeoutMs) {
        Object lock = frameLocks.computeIfAbsent(deviceId, k -> new Object());
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                byte[] current = latestFrames.get(deviceId);
                // 有新帧且不是同一帧
                if (current != null && current != lastFrame) {
                    return current;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null; // 超时
                }
                try {
                    lock.wait(Math.min(remaining, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }
}
