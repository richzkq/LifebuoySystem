package com.lifebuoysystem.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifebuoysystem.service.FrameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Base64;
import java.util.Map;

/**
 * 接收 RK3588 image_pusher.py 推送的 Base64 图片帧
 * <p>
 * 协议：原生 WebSocket (非 STOMP)，JSON 文本帧
 * <pre>
 * {"deviceId": "rocket001", "imageBase64": "data:image/jpeg;base64,/9j/4AAQ..."}
 * </pre>
 *
 * @author ZKQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameWebSocketHandler extends TextWebSocketHandler {

    private final FrameService frameService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("RK3588 推流连接建立 — session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();

            @SuppressWarnings("unchecked")
            Map<String, String> data = objectMapper.readValue(payload, Map.class);

            String deviceId = data.get("deviceId");
            String imageBase64 = data.get("imageBase64");

            if (deviceId == null || imageBase64 == null) {
                log.warn("无效帧数据: deviceId={}, hasImage={}", deviceId, imageBase64 != null);
                return;
            }

            // 去掉 Base64 前缀 "data:image/jpeg;base64,"
            String base64Data = imageBase64;
            if (base64Data.contains(",")) {
                base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
            }

            byte[] imgBytes = Base64.getDecoder().decode(base64Data);
            frameService.storeFrame(deviceId, imgBytes);

            log.debug("帧已接收 deviceId={} size={}KB", deviceId, imgBytes.length / 1024);
        } catch (Exception e) {
            log.error("帧解析失败: {}", e.getMessage());
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
