package com.lifebuoysystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 舵机/救生圈释放配置
 *
 * @author ZKQ
 */
@Data
@Component
@ConfigurationProperties(prefix = "servo")
public class ServoProperties {

    /** 连续 N 帧检测到溺水才触发舵机 */
    private int consecutiveThreshold = 5;

    /** 两次舵机触发之间的冷却时间 (毫秒) */
    private long cooldownMs = 30000;

    /** MQTT topic 前缀 */
    private String mqttTopicPrefix = "lifebuoy/servo";

    /** 构造指定设备的 MQTT 命令 topic */
    public String getCommandTopic(String deviceId) {
        return mqttTopicPrefix + "/" + deviceId + "/command";
    }
}
