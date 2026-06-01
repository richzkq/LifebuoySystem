package com.lifebuoysystem.service;

import com.lifebuoysystem.entity.AlarmRecord;

import java.util.List;

/**
 * @author ZKQ
 */
public interface AlarmService {

    /**
     * 获取最近20条报警记录
     */
    List<AlarmRecord> listAlarms();
}
