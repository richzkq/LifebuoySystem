package com.lifebuoysystem.config;


import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

/**
 * @author ZKQ
 */

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 后端向前端推送的主题前缀
        registry.enableSimpleBroker("/topic");
        // 前端发向后端的前缀（本项目前端只接收，暂不需要，保留备用）
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 握手端点，allowedOriginPatterns("*") 开发期跨域
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();   // 兼容不支持原生 WS 的浏览器
    }
}