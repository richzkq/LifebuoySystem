package com.lifebuoysystem.handler;

import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.service.FrameService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接收 RK3588 二进制图片帧
 * 做三件事：存帧 + 推视频 + 推 STOMP 元数据
 */
@Slf4j
@Component
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private final FrameService frameService;
    private final BrowserFrameHandler browserFrameHandler;

    @Autowired @Lazy
    private SimpMessagingTemplate messagingTemplate;

    private final Map<String, AtomicInteger> frameCounters = new ConcurrentHashMap<>();

    public FrameWebSocketHandler(FrameService frameService, BrowserFrameHandler browserFrameHandler) {
        this.frameService = frameService;
        this.browserFrameHandler = browserFrameHandler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("RK3588 推流连接 — session={}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            ByteBuffer buf = message.getPayload();

            int devIdLen = buf.getInt();
            byte[] devBytes = new byte[devIdLen];
            buf.get(devBytes);
            String deviceId = new String(devBytes, java.nio.charset.StandardCharsets.UTF_8);

            byte[] jpgBytes = new byte[buf.remaining()];
            buf.get(jpgBytes);

            if (jpgBytes.length == 0) return;

            // 1. 存帧
            frameService.storeFrame(deviceId, jpgBytes);

            // 2. 推视频给浏览器
            browserFrameHandler.broadcast(deviceId, jpgBytes);

            // 3. 推元数据到 STOMP（解决仪表盘显示 — 的问题）
            int frameNo = frameCounters.computeIfAbsent(deviceId, k -> new AtomicInteger()).incrementAndGet();
            DeviceStatus status = new DeviceStatus();
            status.setDeviceId(deviceId);
            status.setFrameNo(frameNo);
            status.setUploadTime(LocalDateTime.now());

            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/frames/" + deviceId, status);
            }
        } catch (Exception e) {
            log.error("二进制帧解析失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("RK3588 推流断开 — session={}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("推流异常 — session={}: {}", session.getId(), exception.getMessage());
    }
}
