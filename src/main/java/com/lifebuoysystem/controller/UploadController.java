package com.lifebuoysystem.controller;


import com.lifebuoysystem.entity.FramePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * @author ZKQ
 */

@Slf4j
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
public class UploadController {

    private final SimpMessagingTemplate broker;   // 注入 WebSocket 消息发送器
    private final ObjectMapper objectMapper;

    /**
     * POST /device/upload
     * Content-Type: multipart/form-data
     *
     * Python 脚本传入：deviceId, frameNo, detectCount, targets(JSON), file(JPEG)
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(

        @RequestParam("deviceId") String deviceId,
        @RequestParam("frameNo") Integer frameNo,

        @RequestParam("drowningCount") Integer drowningCount,
        @RequestParam("personCount") Integer personCount,
        @RequestParam("callForHelp") Integer callForHelp,
        @RequestParam("pressure") Integer pressure,
        @RequestParam("alarm") Integer alarm,

        @RequestParam("targets") String targetsJson,
        @RequestParam("file") MultipartFile file


    ) {

        try {

            // ========= 解析目标 =========
            List<FramePayload.Target> targets = objectMapper.readValue(
                    targetsJson,
                    new TypeReference<>() {}
            );

            // ========= 保存图片 =========
            String filename = deviceId + "_latest.jpg";

            Path uploadPath = Paths.get("uploads");

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(filename);

            Files.write(filePath, file.getBytes());

            // ========= 组装推送 =========
            FramePayload payload = new FramePayload();

            payload.setDeviceId(deviceId);
            payload.setFrameNo(frameNo);

            payload.setDrowningCount(drowningCount);
            payload.setPersonCount(personCount);
            payload.setCallForHelp(callForHelp);
            payload.setPressure(pressure);
            payload.setAlarm(alarm);

            payload.setImageUrl("/uploads/" + filename);
            payload.setTargets(targets);

            // ========= WebSocket 推送 =========
            broker.convertAndSend("/topic/frames", payload);

            log.info(
                    "上传成功 device={} frame={} drowning={} call={} pressure={}",
                    deviceId,
                    frameNo,
                    drowningCount,
                    callForHelp,
                    pressure
            );

            return Map.of(
                    "code", 200,
                    "message", "ok"
            );

        } catch (Exception e) {

            log.error("上传处理失败", e);

            return Map.of(
                    "code", 500,
                    "message", e.getMessage()
            );
        }

    }

}