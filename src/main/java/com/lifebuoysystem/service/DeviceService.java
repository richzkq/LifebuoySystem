package com.lifebuoysystem.service;

import com.lifebuoysystem.entity.DeviceStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author ZKQ
 */
public interface DeviceService {

    /**
     * 处理设备上传的一帧数据，包含溺水检测、报警写入、WebSocket推送、文件保存
     */
    String processUpload(String deviceId, Integer frameNo, Integer drowningCount,
                         Integer personCount, Integer callForHelp, Integer pressure,
                         Integer alarm, Double temperature, String targetsJson, MultipartFile file);

    /**
     * 获取设备的最新状态（含呼救锁存：最近30秒内有呼救则持续返回1）
     */
    DeviceStatus getLatestStatus(String deviceId);
}
