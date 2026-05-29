package com.lifebuoysystem.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.entity.TargetInfo;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/device")
@CrossOrigin
public class DeviceController {

    // 内存缓存
    private static final Map<String, DeviceStatus> DEVICE_CACHE =
            new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Long> lastCallForHelpTime = new ConcurrentHashMap<>();

    @Autowired
    private AlarmRecordMapper alarmRecordMapper;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 记录每个设备上一帧是否处于溺水态,用于边沿检测
    private final Map<String, Boolean> lastDrowning = new ConcurrentHashMap<>();

    @PostMapping("/upload")
    public String upload(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("frameNo") Integer frameNo,
            @RequestParam("drowningCount") Integer drowningCount,
            @RequestParam("personCount") Integer personCount,
            @RequestParam("callForHelp") Integer callForHelp,
            @RequestParam("pressure") Integer pressure,
            @RequestParam("alarm") Integer alarm,
            @RequestParam("targets") String targetsJson,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {

        try {

            DeviceStatus status = new DeviceStatus();
            status.setDeviceId(deviceId);
            status.setFrameNo(frameNo);
            status.setDrowningCount(drowningCount);
            status.setPersonCount(personCount);
            status.setCallForHelp(callForHelp);
            status.setPressure(pressure);
            status.setAlarm(alarm);
            status.setUploadTime(LocalDateTime.now());



            // ============ 1. 溺水写库(边沿检测,防刷屏) ============
            boolean nowDrowning = drowningCount != null && drowningCount > 0;
            boolean wasDrowning = lastDrowning.getOrDefault(deviceId, false);

            if (nowDrowning && !wasDrowning) {
                // 从「无溺水」跳变到「有溺水」→ 写一条记录

                alarmRecordMapper.insert(deviceId, "Drowning", "HANDLED");
            }
            lastDrowning.put(deviceId, nowDrowning);

            // 2. 实时状态推送(前端刷新画面) ============
//            log.info("WS push -> /topic/frames/{}, status={}", deviceId, status);
            messagingTemplate.convertAndSend("/topic/frames/" + deviceId, status);

           //3. 呼救声弹窗(只推不写库) ============
            if (callForHelp != null && callForHelp > 0) {

                lastCallForHelpTime.put(deviceId, System.currentTimeMillis());

                Map<String, Object> popup = new HashMap<>();
                popup.put("type", "callForHelp");
                popup.put("deviceId", deviceId);
                popup.put("timestamp", System.currentTimeMillis());
                popup.put("message", "检测到呼救声!");
                messagingTemplate.convertAndSend("/topic/alarm", popup);
            }



            List<TargetInfo> targets =
                    objectMapper.readValue(
                            targetsJson,
                            new TypeReference<List<TargetInfo>>() {}
                    );

            status.setTargets(targets);

            if (file != null && !file.isEmpty()) {

                String uploadDir = "uploads/";

                File dir = new File(uploadDir);

                if (!dir.exists()) {
                    dir.mkdirs();
                }

                String filename =
                        System.currentTimeMillis()
                                + "_"
                                + file.getOriginalFilename();

                File dest = new File(uploadDir + filename);

                file.transferTo(dest);

                status.setImageUrl(
                        "/uploads/" + filename
                );
            }

            DEVICE_CACHE.put(deviceId, status);

            return "ok";

        } catch (Exception e) {

            e.printStackTrace();

            return "error";
        }
    }

    @GetMapping("/latest")
    public DeviceStatus latest(@RequestParam String deviceId) {
        DeviceStatus status = DEVICE_CACHE.get(deviceId);
        if (status == null) {
            return null;
        }
        // 呼救锁存:最近 15 秒内有过呼救,就持续返回 1
        Long lastCall = lastCallForHelpTime.get(deviceId);
        if (lastCall != null && System.currentTimeMillis() - lastCall < 30000) {
            status.setCallForHelp(1);
        }
        return status;
    }
}