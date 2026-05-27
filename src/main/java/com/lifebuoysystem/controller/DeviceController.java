package com.lifebuoysystem.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.entity.TargetInfo;
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

    @PostMapping("/upload")
    public String upload(

            @RequestParam("deviceId") String deviceId,

            @RequestParam("frameNo") Integer frameNo,

            @RequestParam("drowningCount") Integer drowningCount,

            @RequestParam("heardCall") Integer heardCall,

            @RequestParam("pressure") Integer pressure,

            @RequestParam("alarm") Integer alarm,

            @RequestParam("targets") String targetsJson,

            @RequestParam(value = "file", required = false)
            MultipartFile file
    ) {

        try {

            DeviceStatus status = new DeviceStatus();

            status.setDeviceId(deviceId);

            status.setFrameNo(frameNo);

            status.setDrowningCount(drowningCount);

            status.setHeardCall(heardCall);

            status.setPressure(pressure);

            status.setAlarm(alarm);

            status.setUploadTime(LocalDateTime.now());

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
    public DeviceStatus latest(
            @RequestParam String deviceId
    ) {

        return DEVICE_CACHE.get(deviceId);
    }
}