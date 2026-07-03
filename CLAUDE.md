# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Smart Lifebuoy System (智能救生圈系统) — an IoT drowning detection and rescue monitoring platform. An RK3588 edge device runs AI models (RKNN) to detect drowning persons in real-time video, pushes detection results and video frames to a Spring Boot server on Alibaba Cloud (47.243.236.87), which streams them to a Vue 3 web dashboard and a uni-app mobile client.

The system also controls a servo motor via MQTT: when consecutive drowning frames are detected, the server pushes a release command to an ESP8266 board that activates the lifebuoy.

## Build & Deploy Commands

### Backend (Spring Boot 3.4.5 + Java 17 + Maven)

```bash
# Build jar
./mvnw clean package -DskipTests

# Dev run
./mvnw spring-boot:run

# Tests
./mvnw test
./mvnw test -Dtest=LifebuoySystemApplicationTests

# Deploy to production (Alibaba Cloud)
mvn clean package -DskipTests
systemctl restart lifebuoy
```

### Frontend (Vue 3 + Vite + Element Plus + Pinia)

```bash
cd frontend
npm install
npm run dev       # Dev server at localhost:5173, proxies to 47.243.236.87:8080
npm run build     # Production build → dist/

# Deploy to production (Alibaba Cloud, Nginx serves /var/www/lifebuoy/)
cd frontend
npm run build
cp -r dist/* /var/www/lifebuoy/
```

### RK3588 Edge Scripts

```bash
# On the RK3588 board:
cd RK3588
pip install aiohttp watchfiles websockets    # dependencies
python3 push_device.py     # AI inference → HTTP POST /device/upload
python3 image_pusher.py    # Frame monitoring → WebSocket /ws-frame
```

### Database

MySQL 8.0, database name `lifebuoy`. Import the schema dump:
```bash
mysql -u root -p lifebuoy < lifebuoySystem_full_20260528_174751.sql
```

## Architecture

### System Data Flow

```
RK3588 Board (Rockchip)
  ├── push_device.py  ──→  HTTP POST /device/upload   (detection data, FormData)
  └── image_pusher.py ──→  WebSocket /ws-frame        (Base64 JPEG frames, JSON)
                              │
                              ▼
Spring Boot Server (port 8080)  ────  MQTT Broker (port 1883, embedded Moquette)
  │                                                          │
  ├── FrameWebSocketHandler  →  Decode Base64 → FrameService cache
  ├── DeviceController       →  DeviceService (drowning edge-detect + WebSocket push)
  ├── FrameController        →  MJPEG stream / snapshot from FrameService
  ├── AlarmController        →  MySQL alarm_record via AlarmService
  ├── userController         →  JWT auth via UserService
  ├── ServoController        →  Manual servo trigger/status/reset
  ├── ServoServiceImpl       →  Consecutive drowning detection → MQTT publish
  │                              │
  └── WebSocket (STOMP /ws)  →  /topic/frames/{deviceId} (real-time status)
                              →  /topic/alarm (call-for-help / servo popups)
                              │
                              ▼
Clients:
  ├── Vue 3 Web Dashboard  →  STOMP over SockJS + MJPEG <img> stream
  ├── uni-app Mobile App   →  Polls GET /device/latest every 400ms
  └── ESP8266 (舵机)       →  MQTT subscribe lifebuoy/servo/+/command
```

### Backend Package Structure (`src/main/java/com/lifebuoysystem/`)

| Package | Purpose |
|---------|---------|
| `controller/` | Thin HTTP layer — delegates to services. `DeviceController`, `FrameController`, `userController`, `AlarmController`, `ServoController` |
| `service/` | Business logic interfaces: `DeviceService`, `UserService`, `FrameService`, `AlarmService`, `ServoService` |
| `service/impl/` | Implementations with `@Service` + `@RequiredArgsConstructor`. All business logic, caching, WebSocket/MQTT pushes live here |
| `mapper/` | MyBatis annotation-based DAOs (`AlarmRecordMapper`, `userMapper`). No XML mappers |
| `entity/` | POJOs: `DeviceStatus`, `TargetInfo`, `User`, `AlarmRecord` |
| `config/` | `WebSocketConfig` (STOMP /ws + raw WebSocket /ws-frame), `webConfig` (CORS + JWT interceptor + static resources), `MqttBrokerConfig`, `MqttClientConfig`, `ServoProperties` |
| `handler/` | `FrameWebSocketHandler` — receives Base64 frames from RK3588 image_pusher.py |
| `interceptor/` | `jwtInterceptor` — validates `token` header. Whitelist managed by `webConfig.excludePathPatterns` only |
| `utils/` | `jwtUtils` — static JWT create/verify (HMAC256, 7-day expiry) |
| `common/` | `result<T>` — Generic API response wrapper (code, msg, data) |

### Key Business Logic

#### 1. Drowning Detection (DeviceServiceImpl)

Two layers of drowning alarm:

- **Edge detection (single frame)**: Rising edge 0→1 drowning count → writes `alarm_record` table. Prevents duplicate writes on consecutive frames.
- **Consecutive frame detection (multi-frame)**: `ServoServiceImpl.onFrameProcessed()` counts consecutive drowning frames. When counter reaches `servo.consecutive-threshold` (default 5), fires servo via MQTT. Any frame with drowningCount=0 resets the counter. 30-second cooldown prevents repeated triggers.

#### 2. Call-for-Help

Pushed to WebSocket `/topic/alarm` as a popup, NOT persisted to DB. The `/device/latest` endpoint latches `callForHelp=1` for 30 seconds after the last detection.

#### 3. Pressure Sensor Override

When `pressure=1` (person rescued), `compute_alarm()` in `push_device.py` forces `alarm=0` regardless of AI detections.

#### 4. Frame Handling — Two Paths

- **HTTP path**: `POST /device/frame` (multipart) — legacy, still supported
- **WebSocket path**: `/ws-frame` — `image_pusher.py` sends JSON `{deviceId, imageBase64}`. `FrameWebSocketHandler` decodes Base64 → `FrameService.storeFrame()`. This is the recommended path.
- **Consumption**: `GET /device/stream/{id}` (MJPEG, ~10fps) and `GET /device/snapshot/{id}` both read from `FrameService` cache

#### 5. Servo/MQTT (ServoServiceImpl)

- Embedded Moquette MQTT broker on port 1883
- Eclipse Paho client publishes to `lifebuoy/servo/{deviceId}/command`
- Message format: `{"cmd":"RELEASE","deviceId":"...","reason":"CONSECUTIVE_DROWNING","ts":...}`
- ESP8266 subscribes to `lifebuoy/servo/+/command`
- REST endpoints for debug: `GET /api/servo/status`, `POST /api/servo/trigger`, `POST /api/servo/reset`

### Frontend Structure (`frontend/src/`)

| Directory | Purpose |
|-----------|---------|
| `api/` | Axios instance (`http.js`) + per-domain modules: `auth.js`, `alarm.js`, `device.js` |
| `stores/` | Pinia: `auth.js` — token, user state, login/logout |
| `composables/` | `useWebSocket.js` — STOMP connect/subscribe/disconnect lifecycle |
| `config/` | Constants: API endpoints, WebSocket topics, default device, UI params |
| `router/` | Vue Router with navigation guard (no token → /login) |
| `views/` | `Login.vue` (glassmorphism), `DetectionView.vue` (MJPEG + stats + alarm list) |

### Routes & WebSocket Topics

**Frontend Routes**:
| Path | Component | Auth Required |
|------|-----------|---------------|
| `/login` | Login.vue | No |
| `/detection` | DetectionView.vue | Yes |

**WebSocket Topics** (STOMP, `/ws`):
| Topic | Content |
|-------|---------|
| `/topic/frames/{deviceId}` | Real-time `DeviceStatus` JSON on each frame |
| `/topic/alarm` | Popup events: call-for-help, servo trigger |

**Raw WebSocket** (non-STOMP, `/ws-frame`):
| Endpoint | Content |
|----------|---------|
| `/ws-frame` | RK3588 → Server: `{"deviceId":"...", "imageBase64":"data:image/jpeg;base64,..."}` |

### JWT Auth Whitelist

Paths excluded from JWT interception (defined in `webConfig.java`):
`/user/login`, `/login/**`, `/device/**`, `/ws/**`, `/ws-frame/**`, `/uploads/**`, `/alarm/**`, `/api/alarm/**`, `/api/servo/**`, `/error`

Auth header name: `token` (not `Authorization`).

### Configuration Files

| File | Purpose |
|------|---------|
| `application.yml` | Server port, MySQL, MQTT broker, servo thresholds |
| `frontend/.env.development` | `VITE_API_BASE_URL=http://47.243.236.87:8080` |
| `frontend/.env.production` | `VITE_API_BASE_URL=` (empty — Nginx proxies all routes) |
| `frontend/vite.config.js` | Dev proxy: `/api`, `/user`, `/device`, `/ws`, `/uploads` → backend |
| `RK3588/IPconfig.json` | `server_ip`, `server_port`, `upload_path`, `device_id` |

### Configuration Notes

- `application.yml` contains secrets (DB password, DeepSeek API key) — do not commit real credentials.
- MyBatis: `map-underscore-to-camel-case: true` — DB `create_time` → Java `createTime`.
- Servo thresholds: `servo.consecutive-threshold` (frames), `servo.cooldown-ms` (cooldown).
- MQTT broker is embedded (Moquette), no external broker needed.
- Frontend production build uses relative URLs — Nginx must proxy `/api/`, `/user/`, `/device/`, `/ws/`, `/ws-frame/`.
