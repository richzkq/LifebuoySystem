package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.service.FrameService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧缓存服务 — 带 wait/notify 机制
 * <p>
 * storeFrame 和 waitForNewFrame 在同一个锁上同步，消除竞态条件
 *
 * @author ZKQ
 */
@Service
public class FrameServiceImpl implements FrameService {

    private final Map<String, byte[]> latestFrames = new ConcurrentHashMap<>();
    /** 帧序号，每存一帧递增，用于 waitForNewFrame 去重 */
    private final Map<String, Long> frameCounters = new ConcurrentHashMap<>();
    /** 每个设备的帧通知锁 */
    private final Map<String, Object> frameLocks = new ConcurrentHashMap<>();

    @Override
    public void storeFrame(String deviceId, byte[] data) {
        // 在锁内更新帧数据和计数器，然后通知
        Object lock = frameLocks.computeIfAbsent(deviceId, k -> new Object());
        synchronized (lock) {
            latestFrames.put(deviceId, data);
            frameCounters.merge(deviceId, 1L, Long::sum);
            lock.notifyAll();
        }
    }

    @Override
    public byte[] getSnapshot(String deviceId) {
        return latestFrames.get(deviceId);
    }

    @Override
    public byte[] waitForNewFrame(String deviceId, byte[] lastFrame, long timeoutMs) {
        Object lock = frameLocks.computeIfAbsent(deviceId, k -> new Object());
        long deadline = System.currentTimeMillis() + timeoutMs;

        synchronized (lock) {
            // 先检查是否已经有比 lastFrame 更新的帧
            Long currentCounter = frameCounters.get(deviceId);
            long lastCounter = frameCounters.getOrDefault(deviceId + ".sent", 0L);

            while (true) {
                byte[] current = latestFrames.get(deviceId);

                // 有帧，且计数器变化 → 是新帧
                if (current != null && currentCounter != null
                        && !currentCounter.equals(lastCounter)) {
                    frameCounters.put(deviceId + ".sent", currentCounter);
                    return current;
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null; // 超时，发 keepalive
                }

                try {
                    lock.wait(Math.min(remaining, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                // 醒来后重新读取计数器
                currentCounter = frameCounters.get(deviceId);
            }
        }
    }
}
