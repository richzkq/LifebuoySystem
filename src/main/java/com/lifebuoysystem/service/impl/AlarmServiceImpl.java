package com.lifebuoysystem.service.impl;

import com.lifebuoysystem.entity.AlarmRecord;
import com.lifebuoysystem.mapper.AlarmRecordMapper;
import com.lifebuoysystem.service.AlarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author ZKQ
 */
@Service
@RequiredArgsConstructor
public class AlarmServiceImpl implements AlarmService {

    private final AlarmRecordMapper alarmRecordMapper;

    @Override
    public List<AlarmRecord> listAlarms() {
        return alarmRecordMapper.list();
    }
}
