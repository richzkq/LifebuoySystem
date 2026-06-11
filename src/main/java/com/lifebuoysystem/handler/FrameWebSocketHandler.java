package com.lifebuoysystem.handler;

import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.service.DeviceService;
import com.lifebuoysystem.service.FrameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 接收 RK3588 image_pusher.py 推送的二进制图片帧
 * <p>
 * 做三件事：存帧(FrameService) + 推视频(BrowserFrameHandler) + 推元数据(STOMP)
 *
 * @author ZKQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private final FrameService frameService;
    private final BrowserFrameHandler browserFrameHandler;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private SimpMessagingTemplate messagingTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private DeviceService deviceService;

    /** 每设备帧计数器，代替 model 输出的 frameNo */
    private final Map<String, AtomicInteger> frameCounters = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("RK3588 推流连接建立 — session={}", session.getId());
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

            if (jpgBytes.length == 0) {
                log.warn("空帧数据 deviceId={}", deviceId);
                return;
            }

            // 1. 存帧（给快照/MJPEG 用）
            frameService.storeFrame(deviceId, jpgBytes);

            // 2. 推视频给浏览器
            browserFrameHandler.broadcast(deviceId, jpgBytes);

            // 3. 推元数据到 STOMP（补全 alarm/drowning/temp 等字段，避免视频帧覆盖报警状态）
            int frameNo = frameCounters.computeIfAbsent(deviceId, k -> new AtomicInteger()).incrementAndGet();

            DeviceStatus status = new DeviceStatus();
            status.setDeviceId(deviceId);
            status.setFrameNo(frameNo);
            status.setUploadTime(LocalDateTime.now());

            // 从缓存补全元数据字段（温度、报警、溺水人数等）
            if (deviceService != null) {
                DeviceStatus cached = deviceService.getLatestStatus(deviceId);
                if (cached != null) {
                    status.setTemperature(cached.getTemperature());
                    status.setAlarm(cached.getAlarm());
                    status.setDrowningCount(cached.getDrowningCount());
                    status.setCallForHelp(cached.getCallForHelp());
                    status.setPressure(cached.getPressure());
                    status.setPersonCount(cached.getPersonCount());
                }
            }

            messagingTemplate.convertAndSend("/topic/frames/" + deviceId, status);

            log.debug("帧已接收 deviceId={} frameNo={} size={}KB",
                    deviceId, frameNo, jpgBytes.length / 1024);
        } catch (Exception e) {
            log.error("二进制帧解析失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("RK3588 推流连接断开 — session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("推流连接异常 — session={}: {}", session.getId(), exception.getMessage());
    }
}
