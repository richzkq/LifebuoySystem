package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.entity.AlarmRecord;
import com.lifebuoysystem.entity.DeviceStatus;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
import com.lifebuoysystem.service.AlarmService;
import com.lifebuoysystem.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author ZKQ
 */
@RestController
@RequestMapping("/api/alarm")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;
    private final AlarmRecordMapper alarmRecordMapper;
    private final DeviceService deviceService;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping("/list")
    public List<AlarmRecord> list() {
        return alarmService.listAlarms();
    }

    /** 确认报警完成：将 PENDING → COMPLETED，立即推送更新后的状态到 STOMP */
    @PostMapping("/{id}/acknowledge")
    public result acknowledge(@PathVariable Long id) {
        int rows = alarmRecordMapper.acknowledge(id);
        if (rows > 0) {
            // 立即推送更新后的设备状态，让前端报警信息消失
            AlarmRecord record = alarmRecordMapper.findById(id);
            if (record != null) {
                DeviceStatus status = deviceService.getLatestStatus(record.getDeviceId());
                if (status != null) {
                    messagingTemplate.convertAndSend("/topic/frames/" + record.getDeviceId(), status);
                }
            }
            return result.success("报警已确认完成");
        }
        return result.error("确认失败：报警记录不存在");
    }
}
