package com.lifebuoysystem.controller;

import com.lifebuoysystem.service.FrameService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author ZKQ
 */
@RestController
@RequestMapping("/device")
@RequiredArgsConstructor
public class FrameController {

    private final FrameService frameService;

    // ============ 1. 接收板子推来的图 ============
    @PostMapping("/frame")
    public String receiveFrame(
            @RequestParam("deviceId") String deviceId,
            @RequestParam("file") MultipartFile file) {
        try {
            frameService.storeFrame(deviceId, file.getBytes());
            return "ok";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    // ============ 2. 单张最新图（给 <img> 轮询或抓拍用） ============
    @GetMapping("/snapshot/{deviceId}")
    public ResponseEntity<byte[]> snapshot(@PathVariable String deviceId) {
        byte[] data = frameService.getSnapshot(deviceId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(data);
    }

    // ============ 3. MJPEG 流（给前端 <img> 当监控） ============
    @GetMapping("/stream/{deviceId}")
    public void stream(@PathVariable String deviceId,
                       HttpServletResponse response) throws IOException {
        String boundary = "frame";
        response.setContentType(
                "multipart/x-mixed-replace; boundary=" + boundary);
        OutputStream out = response.getOutputStream();

        try {
            while (true) {
                byte[] data = frameService.getSnapshot(deviceId);
                if (data != null) {
                    out.write(("--" + boundary + "\r\n").getBytes());
                    out.write("Content-Type: image/jpeg\r\n".getBytes());
                    out.write(("Content-Length: " + data.length + "\r\n\r\n")
                            .getBytes());
                    out.write(data);
                    out.write("\r\n".getBytes());
                    out.flush();
                }
                Thread.sleep(100); // 约 10fps
            }
        } catch (Exception e) {
            // 前端关闭连接会抛异常，正常结束即可
        }
    }
}
