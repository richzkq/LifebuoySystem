package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.config.ServoProperties;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
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
    private final AlarmRecordMapper alarmRecordMapper;

    // ============ 状态缓存 ============
    /** 每个设备的连续溺水帧计数（不再使用 threshold，保留用于调试） */
    private final Map<String, Integer> consecutiveCounts = new ConcurrentHashMap<>();
    /** 每个设备上次舵机推送时间戳 (ms) */
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    /** 每个设备舵机是否已释放 */
    private final Map<String, Boolean> servoReleased = new ConcurrentHashMap<>();
    /** 每个设备是否处于活跃溺水推送状态 */
    private final Map<String, Boolean> pushActive = new ConcurrentHashMap<>();

    /** 溺水期间推送间隔（毫秒） */
    private static final long PUSH_INTERVAL_MS = 3000;

    // ==================== 核心算法 ====================

    @Override
    public void onFrameProcessed(String deviceId, Integer drowningCount, Integer alarm) {
        if (deviceId == null || drowningCount == null) return;

        long now = System.currentTimeMillis();
        boolean hasPending = alarmRecordMapper.countPending(deviceId) > 0;

        log.info("[舵机] device={} drowning={} alarm={} pending={} pushActive={}",
                 deviceId, drowningCount, alarm, hasPending, pushActive.getOrDefault(deviceId, false));

        if (drowningCount > 0) {
            // 溺水帧：启动或维持推送
            boolean wasActive = pushActive.getOrDefault(deviceId, false);
            pushActive.put(deviceId, true);

            long lastPush = lastTriggerTime.getOrDefault(deviceId, 0L);
            long elapsed = now - lastPush;

            if (!wasActive) {
                // 首次溺水 → 立即推送
                fireServo(deviceId, "CONSECUTIVE_DROWNING");
                lastTriggerTime.put(deviceId, now);
                consecutiveCounts.put(deviceId, 1);
                log.warn("[舵机推送 #1] device={} 首次触发", deviceId);
            } else if (elapsed >= PUSH_INTERVAL_MS) {
                // 持续溺水 → 每隔 PUSH_INTERVAL_MS 推送一次
                int sent = consecutiveCounts.merge(deviceId, 1, Integer::sum);
                fireServo(deviceId, "CONSECUTIVE_DROWNING");
                lastTriggerTime.put(deviceId, now);
                log.warn("[舵机推送 #{}] device={} 持续推送, 距上次 {}ms", sent, deviceId, elapsed);
            } else {
                log.info("[舵机跳过] device={} 距上次推送仅 {}ms, 等待中", deviceId, elapsed);
            }
        } else {
            // drowningCount == 0：模型无溺水，但可能心跳覆盖
            if (!hasPending && pushActive.getOrDefault(deviceId, false)) {
                // DB 无待确认报警 + 之前活跃 → 真正结束
                pushActive.put(deviceId, false);
                consecutiveCounts.put(deviceId, 0);
                log.info("[舵机停止] device={} 报警已确认或自然结束，停止推送", deviceId);
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
            // 确保连接存活再发布
            if (!mqttPublisher.isConnected()) {
                log.warn("MQTT 断开，尝试重连...");
                mqttPublisher.reconnect();
                log.info("MQTT 重连结果: {}", mqttPublisher.isConnected() ? "成功" : "失败");
            }

            String topic = servoProperties.getCommandTopic(deviceId);
            String payload = String.format(
                    "{\"cmd\":\"RELEASE\",\"deviceId\":\"%s\",\"reason\":\"%s\",\"ts\":%d}",
                    deviceId, reason, System.currentTimeMillis()
            );
            MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);
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
