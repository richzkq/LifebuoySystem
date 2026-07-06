#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Servo.h>

// =====================================================
// 1. WiFi 配置
// =====================================================
const char* ssid = "111";
const char* password = "20061019";

// =====================================================
// 2. MQTT 服务器配置
// =====================================================
const char* mqttServer = "10.184.201.174";
const int mqttPort = 1883;

const char* deviceId = "rocket001";
const char* subscribeTopic = "lifebuoy/servo/rocket001/command";

// =====================================================
// 3. 舵机与按钮配置
// =====================================================
const int SERVO_PIN = D8;     // 舵机信号口
const int BUTTON_PIN = D7;    // 复位按钮

// 你的实测结果：
// angle=90 是初始位置 / 锁定位
// angle=180 是释放位置
const int SERVO_LOCK_ANGLE = 90;
const int SERVO_RELEASE_ANGLE = 180;

// 释放后保持时间（毫秒），之后自动复位
const unsigned long SERVO_HOLD_MS = 2000;
const unsigned long DEBOUNCE_MS = 50;

// =====================================================
// 4. 对象定义
// =====================================================
Servo servo;
WiFiClient espClient;
PubSubClient mqttClient(espClient);

// =====================================================
// 5. 状态变量
// =====================================================
bool hasReleased = false;
unsigned long servoReleaseTime = 0;

// 按钮消抖
int lastButtonReading = HIGH;
int stableButtonState = HIGH;
unsigned long lastDebounceTime = 0;

// =====================================================
// WiFi 连接
// =====================================================
void connectWiFi() {
  Serial.println();
  Serial.println("开始连接 WiFi...");

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("WiFi 连接成功！");
  Serial.print("ESP8266 IP 地址：");
  Serial.println(WiFi.localIP());
}

// =====================================================
// MQTT Client ID
// =====================================================
String makeClientId() {
  String mac = WiFi.macAddress();
  mac.replace(":", "");

  String clientId = "lifebuoy-esp8266-";
  clientId += mac.substring(mac.length() - 4);

  return clientId;
}

// =====================================================
// 执行释放
// =====================================================
void startRelease() {
  if (hasReleased) {
    Serial.println("已经释放过，请先按 D7 按钮复位");
    return;
  }

  Serial.println();
  Serial.println("收到 RELEASE，舵机转到释放位置 angle=180");

  servo.write(SERVO_RELEASE_ANGLE);
  hasReleased = true;

  Serial.print("当前舵机角度：");
  Serial.println(SERVO_RELEASE_ANGLE);
}

// =====================================================
// 执行复位
// =====================================================
void startReset() {
  Serial.println();
  Serial.println("执行复位，舵机回到初始位置 angle=90");

  servo.write(SERVO_LOCK_ANGLE);
  hasReleased = false;

  Serial.print("当前舵机角度：");
  Serial.println(SERVO_LOCK_ANGLE);
}

// =====================================================
// D7 按钮处理
// 按钮接法：D7 → 按钮 → GND
// =====================================================
void handleButton() {
  int reading = digitalRead(BUTTON_PIN);

  if (reading != lastButtonReading) {
    lastDebounceTime = millis();
  }

  if ((millis() - lastDebounceTime) > DEBOUNCE_MS) {
    if (reading != stableButtonState) {
      stableButtonState = reading;

      // INPUT_PULLUP 模式下，按下按钮是 LOW
      if (stableButtonState == LOW) {
        Serial.println();
        Serial.println("检测到 D7 按钮按下，执行复位");
        startReset();
      }
    }
  }

  lastButtonReading = reading;
}

// =====================================================
// MQTT 回调函数
// =====================================================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  Serial.println();
  Serial.println("========== 收到 MQTT 消息 ==========");

  Serial.print("Topic：");
  Serial.println(topic);

  String message = "";
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }

  Serial.print("消息内容：");
  Serial.println(message);

  StaticJsonDocument<512> doc;
  DeserializationError error = deserializeJson(doc, message);

  if (error) {
    Serial.print("JSON 解析失败：");
    Serial.println(error.c_str());
    return;
  }

  const char* cmd = doc["cmd"];
  const char* msgDeviceId = doc["deviceId"];
  const char* reason = doc["reason"];

  Serial.print("cmd = ");
  Serial.println(cmd);

  Serial.print("deviceId = ");
  Serial.println(msgDeviceId);

  Serial.print("reason = ");
  Serial.println(reason);

  if (cmd == nullptr || msgDeviceId == nullptr) {
    Serial.println("字段不完整，忽略");
    return;
  }

  if (String(cmd) == "RELEASE" && String(msgDeviceId) == String(deviceId)) {
    Serial.println("确认是本设备 RELEASE 指令");
    startRelease();
  } else {
    Serial.println("不是本设备 RELEASE 指令，忽略");
  }
}

// =====================================================
// MQTT 连接与订阅
// =====================================================
void connectMQTT() {
  while (!mqttClient.connected()) {
    Serial.println();
    Serial.print("开始连接 MQTT 服务器：");
    Serial.print(mqttServer);
    Serial.print(":");
    Serial.println(mqttPort);

    String clientId = makeClientId();

    Serial.print("Client ID：");
    Serial.println(clientId);

    bool connected = mqttClient.connect(clientId.c_str());

    if (connected) {
      Serial.println("MQTT 连接成功！");

      bool subOk = mqttClient.subscribe(subscribeTopic, 1);

      if (subOk) {
        Serial.print("订阅成功：");
        Serial.println(subscribeTopic);
      } else {
        Serial.print("订阅失败：");
        Serial.println(subscribeTopic);
      }

    } else {
      Serial.print("MQTT 连接失败，状态码：");
      Serial.println(mqttClient.state());
      Serial.println("5 秒后重试...");
      delay(5000);
    }
  }
}

// =====================================================
// 串口测试命令
// =====================================================
void handleSerialCommand() {
  if (!Serial.available()) {
    return;
  }

  String cmd = Serial.readStringUntil('\n');
  cmd.trim();

  Serial.print("串口收到命令：");
  Serial.println(cmd);

  if (cmd == "release" || cmd == "open") {
    startRelease();
  }

  else if (cmd == "reset" || cmd == "close") {
    startReset();
  }

  else if (cmd.startsWith("angle=")) {
    int angle = cmd.substring(6).toInt();

    if (angle >= 0 && angle <= 180) {
      servo.write(angle);
      Serial.print("手动设置舵机角度：");
      Serial.println(angle);
    } else {
      Serial.println("角度范围应为 0~180");
    }
  }

  else if (cmd == "unlock") {
    hasReleased = false;
    Serial.println("已清除释放状态，可以再次触发 RELEASE");
  }

  else {
    Serial.println("可用串口命令：");
    Serial.println("release / open  = 转到释放位置 angle=180");
    Serial.println("reset / close   = 复位到初始位置 angle=90");
    Serial.println("angle=90        = 手动转到初始位置");
    Serial.println("angle=180       = 手动转到释放位置");
    Serial.println("unlock          = 清除已释放状态");
  }
}

// =====================================================
// 初始化
// =====================================================
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("ESP8266 普通角度舵机 MQTT 控制端启动");

  pinMode(BUTTON_PIN, INPUT_PULLUP);

  servo.attach(SERVO_PIN);

  // 上电默认回到初始位置 angle=90
  servo.write(SERVO_LOCK_ANGLE);
  hasReleased = false;

  Serial.print("舵机初始化到初始位置 angle=");
  Serial.println(SERVO_LOCK_ANGLE);

  connectWiFi();

  mqttClient.setServer(mqttServer, mqttPort);
  mqttClient.setCallback(mqttCallback);
  mqttClient.setBufferSize(512);

  Serial.println("初始化完成，等待 MQTT 指令或 D7 按钮复位...");
}

// =====================================================
// 主循环
// =====================================================
void loop() {
  if (!mqttClient.connected()) {
    connectMQTT();
  }

  mqttClient.loop();

  handleButton();

  handleSerialCommand();
}