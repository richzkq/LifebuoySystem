/**
 * 智能救生圈 — ESP8266 舵机控制器
 *
 * 功能:
 *   1. 连接 WiFi + MQTT Broker
 *   2. 订阅 lifebuoy/servo/+/command
 *   3. 收到 RELEASE 指令 → 舵机释放救生圈
 *   4. 自动重连 + 看门狗保护
 *
 * 依赖库 (Arduino Library Manager):
 *   - PubSubClient by Nick O'Leary
 *   - ArduinoJson by Benoit Blanchon
 *
 * 硬件接线:
 *   ESP8266 D2 → 舵机信号线 (橙/黄)
 *   ESP8266 G  → 舵机 GND     (棕/黑)
 *   ESP8266 VU → 舵机 VCC     (红, 5V 供电)
 */

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Servo.h>
#include <Ticker.h>

#include "config.h"

// ==================== 全局对象 ====================
WiFiClient    wifiClient;
PubSubClient  mqtt(wifiClient);
Servo         servo;

// ==================== 状态 ====================
bool    servoReleased = false;           // 舵机是否已释放
bool    mqttConnected = false;           // MQTT 连接状态
unsigned long lastStatusTime  = 0;       // 上次输出状态的时间
unsigned long servoReleaseTime = 0;      // 舵机释放的时间
unsigned long lastMqttAttempt = 0;       // 上次 MQTT 重连尝试

// ==================== 看门狗 ====================
Ticker wdtTicker;

void ICACHE_RAM_ATTR wdtReset() {
  ESP.wdtFeed();  // 喂狗，防止复位
}

// ==================== LED 闪烁 ====================
void ledOn()  { digitalWrite(LED_PIN, LOW);  }
void ledOff() { digitalWrite(LED_PIN, HIGH); }

void blinkLED(int times, int msDelay) {
  for (int i = 0; i < times; i++) {
    ledOn();  delay(msDelay);
    ledOff(); delay(msDelay);
  }
}

// ==================== 初始化 ====================
void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println();
  Serial.println("🛟 智能救生圈 ESP8266 舵机控制器");
  Serial.println("=");

  // 引脚
  pinMode(LED_PIN, OUTPUT);
  ledOff();

  // 舵机初始位置: 锁定
  servo.attach(SERVO_PIN);
  servo.write(SERVO_LOCKED_ANGLE);
  delay(500);

  // 连接 WiFi
  Serial.printf("📡 连接 WiFi: %s ...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int wifiAttempts = 0;
  while (WiFi.status() != WL_CONNECTED && wifiAttempts < 40) {
    delay(500);
    Serial.print(".");
    wifiAttempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\n✅ WiFi 已连接, IP: %s\n", WiFi.localIP().toString().c_str());
    blinkLED(2, 200);
  } else {
    Serial.println("\n❌ WiFi 连接失败，继续尝试后台重连...");
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);  // 后台重连
  }

  // MQTT 服务器
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(mqttCallback);
  // 增大 keepalive 和 socket 超时
  mqtt.setKeepAlive(30);
  mqtt.setSocketTimeout(10);

  Serial.printf("🔗 MQTT Broker: %s:%d\n", MQTT_SERVER, MQTT_PORT);
  Serial.printf("📬 订阅 Topic: %s\n", MQTT_TOPIC);

  // 看门狗: 2 秒间隔
  ESP.wdtEnable(WDTO_2S);
  wdtTicker.attach_ms(1500, wdtReset);

  Serial.println("✅ 初始化完成，等待指令...\n");
}

// ==================== MQTT 消息回调 ====================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  // 截取 payload 为字符串
  char json[256];
  unsigned int len = length < 255 ? length : 255;
  memcpy(json, payload, len);
  json[len] = '\0';

  Serial.printf("\n📨 收到 MQTT 消息\n");
  Serial.printf("   Topic:   %s\n", topic);
  Serial.printf("   Payload: %s\n", json);

  // 解析 JSON
  StaticJsonDocument<256> doc;
  DeserializationError err = deserializeJson(doc, json);

  if (err) {
    Serial.printf("❌ JSON 解析失败: %s\n", err.c_str());
    return;
  }

  const char* cmd    = doc["cmd"];
  const char* reason = doc["reason"] | "UNKNOWN";
  const char* devId  = doc["deviceId"] | "UNKNOWN";
  long ts            = doc["ts"] | 0;

  Serial.printf("   命令: %s | 原因: %s | 设备: %s | 时间: %ld\n",
                cmd, reason, devId, ts);

  if (strcmp(cmd, "RELEASE") == 0) {
    releaseServo(reason);
  } else {
    Serial.printf("⚠️ 未知命令: %s\n", cmd);
  }
}

// ==================== 舵机释放 ====================
void releaseServo(const char* reason) {
  if (servoReleased) {
    Serial.println("⚠️ 舵机已处于释放状态（防重复触发），忽略");
    return;
  }

  Serial.printf("🛟🛟🛟 释放救生圈! 原因: %s 🛟🛟🛟\n", reason);
  blinkLED(5, 80);

  servo.write(SERVO_RELEASED_ANGLE);
  servoReleased = true;
  servoReleaseTime = millis();
}

void resetServo() {
  if (!servoReleased) return;

  unsigned long elapsed = millis() - servoReleaseTime;
  if (elapsed >= SERVO_HOLD_MS) {
    Serial.println("🔄 舵机复位（锁定状态）");
    servo.write(SERVO_LOCKED_ANGLE);
    servoReleased = false;
    blinkLED(1, 300);
  }
}

// ==================== MQTT 连接 ====================
bool connectMQTT() {
  if (WiFi.status() != WL_CONNECTED) return false;

  Serial.printf("🔗 连接 MQTT ... ");
  bool ok = mqtt.connect(
    MQTT_CLIENT,          // clientId
    NULL,                 // username (无)
    NULL,                 // password (无)
    MQTT_TOPIC,           // willTopic: 断线时发布到此 topic
    1,                    // willQos
    true,                 // willRetain
    "{\"status\":\"offline\"}"  // willMessage
  );

  if (ok) {
    Serial.println("✅ 已连接");
    mqtt.subscribe(MQTT_TOPIC, 1);
    Serial.printf("📬 已订阅: %s\n", MQTT_TOPIC);
    mqttConnected = true;
    ledOn();  // 常亮 = 在线
  } else {
    Serial.printf("❌ 失败, state=%d\n", mqtt.state());
    mqttConnected = false;
    ledOff();
  }
  return ok;
}

// ==================== 主循环 ====================
void loop() {
  unsigned long now = millis();

  // 1. 看门狗（已在 Ticker 中喂）
  ESP.wdtFeed();

  // 2. 检查 WiFi
  if (WiFi.status() != WL_CONNECTED) {
    // WiFi 断开 → 尝试重连
    static unsigned long lastWifiAttempt = 0;
    if (now - lastWifiAttempt > 10000) {
      Serial.println("📡 WiFi 断开，尝试重连...");
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
      lastWifiAttempt = now;
    }
    ledOff();
    delay(100);
    return;
  }

  // 3. 检查 MQTT
  if (!mqtt.connected()) {
    if (now - lastMqttAttempt > 5000) {
      connectMQTT();
      lastMqttAttempt = now;
    }
    delay(100);
    return;
  }

  // 4. MQTT 消息处理（非阻塞）
  mqtt.loop();

  // 5. 舵机复位检查
  resetServo();

  // 6. 定期输出状态
  if (now - lastStatusTime > STATUS_INTERVAL_MS) {
    Serial.printf("💚 在线 | WiFi RSSI: %d dBm | MQTT: 已连接 | 舵机: %s | 运行: %lu 秒\n",
                  WiFi.RSSI(),
                  servoReleased ? "已释放⚠️" : "锁定🟢",
                  millis() / 1000);
    lastStatusTime = now;
  }

  delay(10);
}
