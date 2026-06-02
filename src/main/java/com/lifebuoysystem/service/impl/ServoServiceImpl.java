package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.config.ServoProperties;
import com.lifebuoysystem.service.ServoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 舵机控制服务实现
 * <p>
 * 核心算法：连续帧计数
 * <pre>
 *   每帧 drowningCount > 0  →  counter++ (ConcurrentHashMap.merge, 线程安全)
 *   每帧 drowningCount == 0 →  counter = 0  (序列中断)
 *   当 counter >= threshold 且冷却时间已过  →  触发舵机
 * </pre>
 *
 * @author ZKQ
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServoServiceImpl implements ServoService {

    private final MqttClient mqttPublisher;
    private final ServoProperties servoProperties;
    private final SimpMessagingTemplate messagingTemplate;

    // ============ 状态缓存 ============
    /** 每个设备的连续溺水帧计数 */
    private final Map<String, Integer> consecutiveCounts = new ConcurrentHashMap<>();
    /** 每个设备上次舵机触发时间戳 (ms) */
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    /** 每个设备舵机是否已释放 */
    private final Map<String, Boolean> servoReleased = new ConcurrentHashMap<>();

    // ==================== 核心算法 ====================

    @Override
    public void onFrameProcessed(String deviceId, Integer drowningCount, Integer alarm) {
        if (deviceId == null || drowningCount == null) return;

        // 综合报警为 0（pressure=1 或显式解除）→ 强制复位
        if (alarm != null && alarm == 0) {
            Integer prev = consecutiveCounts.put(deviceId, 0);
            if (prev != null && prev > 0) {
                log.info("设备 {} 报警解除 (alarm=0)，舵机计数复位 (之前连续 {} 帧)", deviceId, prev);
            }
            return;
        }

        if (drowningCount > 0) {
            // 原子递增连续计数
            int count = consecutiveCounts.merge(deviceId, 1, Integer::sum);
            log.debug("设备 {} 连续溺水帧: {}/{}",
                    deviceId, count, servoProperties.getConsecutiveThreshold());

            if (count >= servoProperties.getConsecutiveThreshold()) {
                long now = System.currentTimeMillis();
                long lastTime = lastTriggerTime.getOrDefault(deviceId, 0L);

                if (now - lastTime >= servoProperties.getCooldownMs()) {
                    fireServo(deviceId, "CONSECUTIVE_DROWNING");
                    consecutiveCounts.put(deviceId, 0);
                    lastTriggerTime.put(deviceId, now);
                } else {
                    log.debug("设备 {} 舵机冷却中，跳过触发 (距上次 {}ms)",
                            deviceId, now - lastTime);
                }
            }
        } else {
            // 溺水中断 → 计数归零
            Integer prev = consecutiveCounts.put(deviceId, 0);
            if (prev != null && prev > 0) {
                log.debug("设备 {} 溺水序列中断 (连续 {} 帧后归零)", deviceId, prev);
            }
        }
    }

    // ==================== 舵机触发 ====================

    private void fireServo(String deviceId, String reason) {
        log.warn("🛟 触发舵机! deviceId={}, reason={}", deviceId, reason);

        // 1. MQTT 推送给 ESP8266
        publishMqttCommand(deviceId, reason);

        // 2. 更新内存状态
        servoReleased.put(deviceId, true);

        // 3. WebSocket 推送给前端监控页面
        Map<String, Object> wsMsg = new LinkedHashMap<>();
        wsMsg.put("type", "servoTrigger");
        wsMsg.put("deviceId", deviceId);
        wsMsg.put("reason", reason);
        wsMsg.put("timestamp", System.currentTimeMillis());
        wsMsg.put("message", "🛟 救生圈已释放!");
        messagingTemplate.convertAndSend("/topic/alarm", wsMsg);
    }

    private void publishMqttCommand(String deviceId, String reason) {
        try {
            String topic = servoProperties.getCommandTopic(deviceId);
            String payload = String.format(
                    "{\"cmd\":\"RELEASE\",\"deviceId\":\"%s\",\"reason\":\"%s\",\"ts\":%d}",
                    deviceId, reason, System.currentTimeMillis()
            );
            MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);      // 至少一次送达
            msg.setRetained(false);
            mqttPublisher.publish(topic, msg);
            log.info("MQTT 已发布 → {} : {}", topic, payload);
        } catch (Exception e) {
            log.error("MQTT 发布失败 deviceId={}: {}", deviceId, e.getMessage());
        }
    }

    // ==================== REST 手动控制 ====================

    @Override
    public void manualTrigger(String deviceId) {
        fireServo(deviceId, "MANUAL_TRIGGER");
    }

    @Override
    public void reset(String deviceId) {
        consecutiveCounts.put(deviceId, 0);
        servoReleased.put(deviceId, false);
        log.info("设备 {} 舵机状态已复位", deviceId);
    }

    @Override
    public Map<String, Object> getStatus(String deviceId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("deviceId", deviceId);
        status.put("consecutiveCount", consecutiveCounts.getOrDefault(deviceId, 0));
        status.put("servoReleased", servoReleased.getOrDefault(deviceId, false));
        status.put("lastTriggerTime", lastTriggerTime.getOrDefault(deviceId, 0L));
        status.put("threshold", servoProperties.getConsecutiveThreshold());
        status.put("cooldownMs", servoProperties.getCooldownMs());
        return status;
    }

    @Override
    public Map<String, Object> getAllStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> allDevices = new HashSet<>();
        allDevices.addAll(consecutiveCounts.keySet());
        allDevices.addAll(lastTriggerTime.keySet());
        allDevices.addAll(servoReleased.keySet());
        for (String deviceId : allDevices) {
            result.put(deviceId, getStatus(deviceId));
        }
        return result;
    }
}
