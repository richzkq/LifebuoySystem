package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.service.FrameService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ZKQ
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
}
