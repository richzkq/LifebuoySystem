/**
 * ESP8266 智能救生圈舵机控制器 — 配置文件
 *
 * 部署前修改下面三个配置：
 *   1. WIFI_SSID / WIFI_PASSWORD  — 你的 WiFi
 *   2. MQTT_SERVER                — 服务器 IP（默认 47.83.199.93）
 *   3. SERVO_PIN / LED_PIN        — 舵机和指示灯引脚
 */

#ifndef CONFIG_H
#define CONFIG_H

// ==================== WiFi ====================
#define WIFI_SSID     "你的WiFi名称"
#define WIFI_PASSWORD "你的WiFi密码"

// ==================== MQTT ====================
#define MQTT_SERVER   "47.83.199.93"
#define MQTT_PORT     1883
#define MQTT_CLIENT   "lifebuoy-esp8266"
#define MQTT_TOPIC    "lifebuoy/servo/+/command"   // + 号匹配任意 deviceId

// ==================== 硬件引脚 ====================
#define SERVO_PIN     D2    // 舵机信号线 (GPIO4)
#define LED_PIN       D4    // 板载 LED (GPIO2), 低电平亮

// ==================== 舵机参数 ====================
// 舵机角度范围（通常 SG90: 0-180 度）
#define SERVO_LOCKED_ANGLE    0     // 锁定状态角度（救生圈在位）
#define SERVO_RELEASED_ANGLE  90    // 释放状态角度（推下救生圈）

// 释放后保持时间（毫秒），然后复位
#define SERVO_HOLD_MS   2000

// ==================== 心跳 ====================
// 每隔 N 毫秒串口输出一次状态
#define STATUS_INTERVAL_MS  10000

#endif
