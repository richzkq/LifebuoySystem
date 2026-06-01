package com.lifebuoysystem.config;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * MQTT 发布客户端 (Eclipse Paho)
 * 用于向 ESP8266 发送舵机控制指令
 * <p>
 * Broker 不可用时不会阻塞应用启动 — ServoServiceImpl 内部有 try/catch 处理 publish 异常
 *
 * @author ZKQ
 */
@Configuration
@Slf4j
public class MqttClientConfig {

    @Value("${mqtt.broker.port:1883}")
    private int port;

    @Bean(destroyMethod = "disconnect")
    @DependsOn("moquetteServer")
    public MqttClient mqttPublisher() throws MqttException {
        String brokerUrl = "tcp://127.0.0.1:" + port;
        String clientId = "lifebuoy-server-" + System.currentTimeMillis();

        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);

        // 重试连接（Broker 刚启动可能还没完全就绪）
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                client.connect(opts);
                log.info("MQTT 发布客户端已连接 — {}", brokerUrl);
                return client;
            } catch (MqttException e) {
                if (i == maxRetries - 1) {
                    log.warn("MQTT Broker 不可用 (已重试 {} 次)，舵机推送暂不生效 — 其余功能正常", maxRetries);
                    return client; // 返回未连接客户端，不阻塞启动
                }
                log.warn("MQTT 连接 {}/{} 失败，2秒后重试...", i + 1, maxRetries);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        return client;
    }
}
