package com.lifebuoysystem.handler;

import com.lifebuoysystem.service.FrameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.nio.ByteBuffer;

/**
 * 接收 RK3588 image_pusher.py 推送的二进制图片帧
 * <p>
 * 协议（精简高效，无 Base64 开销）：
 * <pre>
 * [4 字节 大端 uint32: deviceId 长度]
 * [N 字节: deviceId UTF-8]
 * [剩余: 原始 JPEG 字节]
 * </pre>
 *
 * @author ZKQ
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameWebSocketHandler extends BinaryWebSocketHandler {

    private final FrameService frameService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("RK3588 推流连接建立 — session={}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            ByteBuffer buf = message.getPayload();

            // 读取 deviceId 长度（前4字节，大端 uint32）
            int devIdLen = buf.getInt();

            // 读取 deviceId
            byte[] devBytes = new byte[devIdLen];
            buf.get(devBytes);
            String deviceId = new String(devBytes, java.nio.charset.StandardCharsets.UTF_8);

            // 剩余全部是 JPEG 数据
            byte[] jpgBytes = new byte[buf.remaining()];
            buf.get(jpgBytes);

            if (jpgBytes.length == 0) {
                log.warn("空帧数据 deviceId={}", deviceId);
                return;
            }

            frameService.storeFrame(deviceId, jpgBytes);
            log.debug("帧已接收 deviceId={} size={}KB", deviceId, jpgBytes.length / 1024);
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
