package com.lifebuoysystem.controller;

import com.lifebuoysystem.entity.AlarmRecord;
import com.lifebuoysystem.service.AlarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author ZKQ
 */
@RestController
@RequestMapping("/api/alarm")
@RequiredArgsConstructor
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping("/list")
    public List<AlarmRecord> list() {
        return alarmService.listAlarms();
    }
}
