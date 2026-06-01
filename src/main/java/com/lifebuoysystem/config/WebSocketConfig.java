package com.lifebuoysystem.config;

import com.lifebuoysystem.handler.FrameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

/**
 * WebSocket 配置
 * <ul>
 *   <li>/ws       — STOMP over SockJS (前端监控页面实时数据)</li>
 *   <li>/ws-frame — 原生 WebSocket (RK3588 image_pusher.py Base64 推流)</li>
 * </ul>
 *
 * @author ZKQ
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private final FrameWebSocketHandler frameWebSocketHandler;

    // ==================== STOMP 端点 ====================

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    // ==================== 原生 WebSocket 端点 (RK3588 推流) ====================

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(frameWebSocketHandler, "/ws-frame")
                .setAllowedOriginPatterns("*");
    }
}
