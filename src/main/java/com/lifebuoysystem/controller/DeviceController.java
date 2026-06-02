package com.lifebuoysystem.controller;

import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author ZKQ
 */
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping("/upload")
    public String upload(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("frameNo") Integer frameNo,
            @RequestParam("drowningCount") Integer drowningCount,
            @RequestParam("personCount") Integer personCount,
            @RequestParam("callForHelp") Integer callForHelp,
            @RequestParam("pressure") Integer pressure,
            @RequestParam("alarm") Integer alarm,
            @RequestParam(value = "temperature", defaultValue = "0.0") Double temperature,
            @RequestParam("targets") String targetsJson,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        return deviceService.processUpload(
                deviceId, frameNo, drowningCount, personCount,
                callForHelp, pressure, alarm, temperature, targetsJson, file
        );
    }

    @GetMapping("/latest")
    public DeviceStatus latest(@RequestParam String deviceId) {
        return deviceService.getLatestStatus(deviceId);
    }
}
