package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.service.ServoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 舵机控制 REST 接口 — 手动触发 / 状态查询 / 复位
 *
 * @author ZKQ
 */
@RestController
@RequestMapping("/api/servo")
@RequiredArgsConstructor
public class ServoController {

    private final ServoService servoService;

    /**
     * 查询舵机状态
     * @param deviceId 可选，不传则返回所有设备
     */
    @GetMapping("/status")
    public result status(@RequestParam(required = false) String deviceId) {
        if (deviceId != null && !deviceId.isEmpty()) {
            return result.success(servoService.getStatus(deviceId));
        }
        return result.success(servoService.getAllStatus());
    }

    /**
     * 手动触发舵机 (用于测试)
     */
    @PostMapping("/trigger")
    public result trigger(@RequestParam String deviceId) {
        servoService.manualTrigger(deviceId);
        return result.success("舵机触发指令已发送, deviceId=" + deviceId);
    }

    /**
     * 复位指定设备的连续计数和释放状态
     */
    @PostMapping("/reset")
    public result reset(@RequestParam String deviceId) {
        servoService.reset(deviceId);
        return result.success("舵机状态已复位, deviceId=" + deviceId);
    }
}
