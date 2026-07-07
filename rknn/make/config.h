// config.h
#pragma once

// 摄像头参数
#define CAMERA_DEVICE   "/dev/video21"
#define CAPTURE_WIDTH   640
#define CAPTURE_HEIGHT  480

// 模型参数
#define MODEL_WIDTH     640
#define MODEL_HEIGHT    640

// 温度阈值：室外太阳下演示版，80°C 容易过热重启，所以更提前降速/暂停
#define TEMP_HIGH       68
#define TEMP_MID        58
#define TEMP_LOW        45
#define TEMP_CRITICAL   74

// 检测参数
// CONF_THRESH 保留作为默认阈值；实际双模型分别使用下面两个阈值。
#define CONF_THRESH             0.50f
#define DROWNING_CONF_THRESH    0.50f
#define PERSON_CONF_THRESH      0.50f

#define NMS_THRESH      0.45f
#define DROWNING_CLASS_ID 0
#define PERSON_CLASS_ID   0

// 后处理参数（YOLOv8 DFL）
#define REG_MAX         16
#define BOX_CHANNELS    64

// 防误报：最近 8 次 Drowning 推理中，至少 4 次有结果才确认输出
#define DROWNING_CONFIRM_WINDOW 8
#define DROWNING_CONFIRM_MIN    4

// 串口停止：连续 5 秒没有 confirmed Drowning，就发送一次 target_id/x/y/w/h 全 0
#define DROWNING_STOP_TIMEOUT_MS 5000
#define DROWNING_STOP_REPEAT_MS  1000

// 运行控制
#define PERSON_INTERVAL     2
#define LOG_EVERY_N         5
#define SAVE_EVERY_N        1
#define TEMP_CHECK_INTERVAL 30
#define GPIO_UPDATE_INTERVAL 5

// 温度控制：只控制模型运行速率，不改变模型输入/输出格式
#define TEMP_READ_INTERVAL_MS       1000
#define TEMP_LOG_INTERVAL_MS        30000
#define TEMP_CRITICAL_SLEEP_MS      500

// 调试控制
#define DEBUG_DROWNING_POST 0
#define MAX_DEBUG_DET_PRINT 12

// GPIO
#define TARGET_GPIO     117

// 串口参数
#define UART_DEVICE "/dev/ttyS9"
#define UART_BAUD 115200

// 保存目录
#define SAVE_DIR        "/dev/shm/frames_int8_pixel_box"
