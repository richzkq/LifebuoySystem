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
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");  // 禁用 Nginx 缓冲
        OutputStream out = response.getOutputStream();

        byte[] lastData = null;
        try {
            while (true) {
                // 阻塞等待新帧（有 notify 唤醒，零轮询延迟）
                // 2 秒超时发 keepalive 防止 Nginx 代理断开
                byte[] data = frameService.waitForNewFrame(deviceId, lastData, 2000);

                if (data == null) {
                    // 超时无新帧，发 keepalive
                    out.write(("--" + boundary + "\r\n").getBytes());
                    out.write("Content-Type: text/plain\r\n\r\n".getBytes());
                    out.write("ping\r\n".getBytes());
                    out.flush();
                    continue;
                }

                // 新帧到达，立即推送
                out.write(("--" + boundary + "\r\n").getBytes());
                out.write("Content-Type: image/jpeg\r\n".getBytes());
                out.write(("Content-Length: " + data.length + "\r\n\r\n")
                        .getBytes());
                out.write(data);
                out.write("\r\n".getBytes());
                out.flush();
                lastData = data;
            }
        } catch (Exception e) {
            // 前端关闭连接会抛异常，正常结束
        }
    }
}
