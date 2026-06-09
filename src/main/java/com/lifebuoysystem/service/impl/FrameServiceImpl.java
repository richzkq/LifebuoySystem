package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.service.FrameService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧缓存 — 纯 ConcurrentHashMap，零锁，零竞争
 */
@Service
public class FrameServiceImpl implements FrameService {

    private final Map<String, byte[]> latestFrames = new ConcurrentHashMap<>();

    @Override
    public void storeFrame(String deviceId, byte[] data) {
        latestFrames.put(deviceId, data);
    }

    @Override
    public byte[] getSnapshot(String deviceId) {
        return latestFrames.get(deviceId);
    }

    @Override
    public byte[] waitForNewFrame(String deviceId, byte[] lastFrame, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] current = latestFrames.get(deviceId);
            if (current != null && current != lastFrame) {
                return current;
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { break; }
        }
        return null;
    }
}
