package com.lifebuoysystem.config;

import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 嵌入式 MQTT Broker (Moquette)
 * 随 Spring Boot 启动，监听 1883 端口，供 ESP8266 直连
 *
 * @author ZKQ
 */
@Configuration
@Slf4j
public class MqttBrokerConfig {

    @Value("${mqtt.broker.port:1883}")
    private int port;

    @Value("${mqtt.broker.host:0.0.0.0}")
    private String host;

    @Bean(destroyMethod = "stopServer")
    public Server moquetteServer() {
        Server server = new Server();
        try {
            log.info("启动嵌入式 Moquette MQTT Broker — {}:{} ...", host, port);

            Properties props = new Properties();
            props.setProperty("port", String.valueOf(port));
            props.setProperty("host", host);
            props.setProperty("allow_anonymous", "true");

            IConfig config = new MemoryConfig(props);
            server.startServer(config);

            log.info("Moquette MQTT Broker 已启动，监听端口 {}", port);
        } catch (Exception e) {
            log.error("MQTT Broker 启动失败 (ESP8266 舵机控制暂不可用): {}", e.getMessage());
            // 不抛出异常 — MQTT 不可用时其余功能正常工作
        }
        return server;
    }
}
