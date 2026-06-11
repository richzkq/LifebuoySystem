package com.lifebuoysystem.controller;

import com.lifebuoysystem.common.result;
import com.lifebuoysystem.entity.AlarmRecord;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
import com.lifebuoysystem.service.AlarmService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/list")
    public List<AlarmRecord> list() {
        return alarmService.listAlarms();
    }

    /** 确认报警完成：将 PENDING → COMPLETED，解除网页/uni-app/舵机的报警状态 */
    @PostMapping("/{id}/acknowledge")
    public result acknowledge(@PathVariable Long id) {
        int rows = alarmRecordMapper.acknowledge(id);
        if (rows > 0) {
            return result.success("报警已确认完成");
        }
        return result.error("确认失败：报警记录不存在");
    }
}
