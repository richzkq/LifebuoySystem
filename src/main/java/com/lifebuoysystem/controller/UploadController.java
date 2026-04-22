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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
            @RequestParam("deviceId")    String deviceId,
            @RequestParam("frameNo")     Integer frameNo,
            @RequestParam("detectCount") Integer detectCount,
            @RequestParam("targets")     String targetsJson,
            @RequestParam("file") MultipartFile file) {

        try {
            // ── 解析目标列表 ──────────────────────────────────────
            List<FramePayload.Target> targets = objectMapper.readValue(
                    targetsJson, new TypeReference<>() {});

            // ── 图片转 Base64（前端直接用 <img :src="..."> 渲染）──
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            String imageBase64 = "data:image/jpeg;base64," + base64;

            // ── 组装推送体 ─────────────────────────────────────────
            FramePayload payload = new FramePayload();
            payload.setDeviceId(deviceId);
            payload.setFrameNo(frameNo);
            payload.setDetectCount(detectCount);
            payload.setImageBase64(imageBase64);
            payload.setTargets(targets);

            // ── 向所有订阅 /topic/frames 的 Vue 客户端推送 ─────────
            broker.convertAndSend("/topic/frames", payload);

            log.info("推送成功: deviceId={} frameNo={} targets={}",
                    deviceId, frameNo, targets.size());

            return Map.of("code", 200, "message", "ok");

        } catch (Exception e) {
            log.error("上传处理失败", e);
            return Map.of("code", 500, "message", e.getMessage());
        }
    }
}