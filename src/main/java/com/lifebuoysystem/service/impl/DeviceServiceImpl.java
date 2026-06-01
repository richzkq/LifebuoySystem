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
    private final Map<String, Long> lastCallForHelpTime = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============ 注入依赖 ============
    private final AlarmRecordMapper alarmRecordMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final ServoService servoService;

    @Override
    public String processUpload(String deviceId, Integer frameNo, Integer drowningCount,
                                Integer personCount, Integer callForHelp, Integer pressure,
                                Integer alarm, String targetsJson, MultipartFile file) {
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
            status.setUploadTime(LocalDateTime.now());

            // ============ 1. 溺水写库（边沿检测，防刷屏） ============
            boolean nowDrowning = drowningCount != null && drowningCount > 0;
            boolean wasDrowning = lastDrowning.getOrDefault(deviceId, false);

            if (nowDrowning && !wasDrowning) {
                alarmRecordMapper.insert(deviceId, "Drowning", "HANDLED");
            }
            lastDrowning.put(deviceId, nowDrowning);

            // ============ 1b. 连续多帧溺水检测 → 舵机释放 ============
            servoService.onFrameProcessed(deviceId, drowningCount);

            // ============ 2. 实时状态推送（WebSocket） ============
            messagingTemplate.convertAndSend("/topic/frames/" + deviceId, status);

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
        return status;
    }
}
