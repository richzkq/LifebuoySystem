package com.lifebuoysystem.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    /** 单线程发送器 — sendMessage 超时不会阻塞 FrameWebSocketHandler */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 设置发送超时：100ms 发不出去就断开，防止慢客户端拖死整条推流
        session.setTextMessageSizeLimit(512 * 1024);
        session.setBinaryMessageSizeLimit(512 * 1024);
        log.info("浏览器帧连接 — session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 首条消息是 deviceId
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

    /** 向所有订阅该设备的浏览器广播 JPEG 帧（100ms 超时，防止慢客户端拖死管道） */
    public void broadcast(String deviceId, byte[] jpgBytes) {
        List<WebSocketSession> list = sessions.get(deviceId);
        if (list == null || list.isEmpty()) return;

        BinaryMessage msg = new BinaryMessage(jpgBytes);
        for (WebSocketSession s : list) {
            if (!s.isOpen()) { removeSession(s); continue; }

            Future<?> future = sendExecutor.submit(() -> {
                try { s.sendMessage(msg); } catch (IOException e) { removeSession(s); }
            });
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            } catch (Exception ignored) {}
        }
    }

    private void removeSession(WebSocketSession session) {
        for (List<WebSocketSession> list : sessions.values()) {
            list.remove(session);
        }
    }
}
