package com.lifebuoysystem.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 浏览器帧推送 — 服务器收到 RK3588 的帧后，直接通过 WebSocket 推送给浏览器
 * <p>
 * 协议: 浏览器连接后先发 deviceId 文本消息，之后接收原始 JPEG 二进制帧
 * 延迟: 服务器收到帧 → 浏览器收到帧，零中间缓存
 *
 * @author ZKQ
 */
@Slf4j
@Component
public class BrowserFrameHandler extends AbstractWebSocketHandler {

    /** deviceId → 浏览器连接列表 */
    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    /** 发送线程池：最多 4 个并行发送（通常只有 1-2 个浏览器连接） */
    private final ExecutorService sendPool = Executors.newFixedThreadPool(4);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(128 * 1024);
        session.setBinaryMessageSizeLimit(256 * 1024);
        log.info("浏览器帧连接 — session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String deviceId = message.getPayload().trim();
        sessions.computeIfAbsent(deviceId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("浏览器订阅帧 deviceId={} session={}", deviceId, session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        // 浏览器不发二进制，忽略
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
        log.info("浏览器帧连接断开 — session={}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeSession(session);
        log.error("浏览器帧连接异常 — session={}: {}", session.getId(), exception.getMessage());
    }

    /** 向所有订阅该设备的浏览器广播原始 JPEG 帧（异步并行，互不阻塞） */
    public void broadcast(String deviceId, byte[] jpgBytes) {
        List<WebSocketSession> list = sessions.get(deviceId);
        if (list == null || list.isEmpty()) return;

        for (WebSocketSession s : list) {
            if (!s.isOpen()) {
                removeSession(s);
                continue;
            }
            // 每个客户端独立异步发送，慢客户端不阻塞其他人
            sendPool.submit(() -> {
                try {
                    s.sendMessage(new BinaryMessage(jpgBytes));
                } catch (Exception e) {
                    removeSession(s);
                }
            });
        }
    }

    private void removeSession(WebSocketSession session) {
        for (List<WebSocketSession> list : sessions.values()) {
            list.remove(session);
        }
    }
}
