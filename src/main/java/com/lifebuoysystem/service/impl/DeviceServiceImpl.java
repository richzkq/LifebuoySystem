package com.lifebuoysystem.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.entity.TargetInfo;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
import com.lifebuoysystem.service.DeviceService;
import com.lifebuoysystem.service.ServoService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ZKQ
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    // ============ 内存缓存 ============
    private final Map<String, DeviceStatus> deviceCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lastDrowning = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastPressure = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallForHelpTime = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============ 注入依赖 ============
    private final AlarmRecordMapper alarmRecordMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ServoService servoService;

    @Override
    public String processUpload(String deviceId, Integer frameNo, Integer drowningCount,
                                Integer personCount, Integer callForHelp, Integer pressure,
                                Integer alarm, Double temperature, String targetsJson, MultipartFile file) {
        try {
            // 构建 DeviceStatus
            DeviceStatus status = new DeviceStatus();
            status.setDeviceId(deviceId);
            status.setFrameNo(frameNo);
            status.setDrowningCount(drowningCount);
            status.setPersonCount(personCount);
            status.setCallForHelp(callForHelp);
            status.setPressure(pressure);
            status.setAlarm(alarm);
            status.setTemperature(temperature);
            status.setUploadTime(LocalDateTime.now());

            // ============ 1. 溺水写库（边沿检测 + 已有未确认报警则不重复写入） ============
            boolean nowDrowning = drowningCount != null && drowningCount > 0;
            boolean wasDrowning = lastDrowning.getOrDefault(deviceId, false);

            if (nowDrowning && !wasDrowning && alarmRecordMapper.countPending(deviceId) == 0) {
                alarmRecordMapper.insert(deviceId, "Drowning", "PENDING");
            }
            lastDrowning.put(deviceId, nowDrowning);

            // ============ 1a. 压力传感器触发 → 自动确认报警 ============
            int nowPressure = pressure != null ? pressure : 0;
            int wasPressure = lastPressure.getOrDefault(deviceId, 0);
            if (nowPressure == 1 && wasPressure == 0) {
                int rows = alarmRecordMapper.autoCompleteByPressure(deviceId);
                if (rows > 0) {
                    log.info("设备 {} 压力传感器触发，自动确认 {} 条报警", deviceId, rows);
                }
            }
            lastPressure.put(deviceId, nowPressure);

            // ============ 1b. 连续多帧溺水检测 → 舵机释放 ============
            servoService.onFrameProcessed(deviceId, drowningCount, alarm);

            // ============ 2. 实时状态推送（WebSocket） ============
            messagingTemplate.convertAndSend("/topic/frames/" + deviceId, status);

            // ============ 2b. WebSocket 弹窗 ============
            if (alarm != null && alarm > 0) {
                Map<String, Object> alarmPopup = new HashMap<>();
                alarmPopup.put("type", "drowningAlarm");
                alarmPopup.put("deviceId", deviceId);
                alarmPopup.put("timestamp", System.currentTimeMillis());
                alarmPopup.put("message", "检测到溺水报警!");
                messagingTemplate.convertAndSend("/topic/alarm", alarmPopup);
            }

            // ============ 3. 呼救声弹窗（只推不写库） ============
            if (callForHelp != null && callForHelp > 0) {
                lastCallForHelpTime.put(deviceId, System.currentTimeMillis());

                Map<String, Object> popup = new HashMap<>();
                popup.put("type", "callForHelp");
                popup.put("deviceId", deviceId);
                popup.put("timestamp", System.currentTimeMillis());
                popup.put("message", "检测到呼救声!");
                messagingTemplate.convertAndSend("/topic/alarm", popup);
            }

            // ============ 4. 解析目标坐标 ============
            List<TargetInfo> targets = objectMapper.readValue(
                    targetsJson,
                    new TypeReference<List<TargetInfo>>() {}
            );
            status.setTargets(targets);

            // ============ 5. 文件保存 ============
            if (file != null && !file.isEmpty()) {
                String uploadDir = "uploads/";
                File dir = new File(uploadDir);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
                File dest = new File(uploadDir + filename);
                file.transferTo(dest);
                status.setImageUrl("/uploads/" + filename);
            }

            // ============ 6. 写入缓存 ============
            deviceCache.put(deviceId, status);

            return "ok";
        } catch (Exception e) {
            e.printStackTrace();
            return "error";
        }
    }

    @Override
    public DeviceStatus getLatestStatus(String deviceId) {
        DeviceStatus status = deviceCache.get(deviceId);
        if (status == null) {
            return null;
        }
        // 呼救锁存：最近30秒内有过呼救，就持续返回1
        Long lastCall = lastCallForHelpTime.get(deviceId);
        if (lastCall != null && System.currentTimeMillis() - lastCall < 30000) {
            status.setCallForHelp(1);
        }
        // 报警锁存：数据库中有未确认的溺水报警 → 持续返回 alarm=1
        if (alarmRecordMapper.countPending(deviceId) > 0) {
            status.setAlarm(1);
        }
        return status;
    }
}
