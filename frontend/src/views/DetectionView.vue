<template>
  <div class="app">
    <div class="background-overlay"></div>

    <header class="header">
      <div class="header-content">
        <span class="title">🛟 SMART LIFEBUOY 实时监控</span>

        <div class="status-bar">
          <span class="dot" :class="connected ? 'dot--on' : 'dot--off'" />
          <span>
            {{ connected ? 'SERVER ONLINE' : 'DISCONNECTED' }}
          </span>

          <span class="divider">|</span>

          <span>
            设备: {{ frame.deviceId || '—' }}
          </span>

          <span class="divider">|</span>

          <span>
            帧号: {{ frame.frameNo ?? '—' }}
          </span>

          <span class="divider">|</span>

          <span>
            报警: <span :style="{ color: frame.alarm === 1 ? '#EF4444' : '#10B981' }">{{ frame.alarm === 1 ? '溺水' : '正常' }}</span>
          </span>
        </div>
      </div>
    </header>

    <main class="main">

      <section class="panel panel--image glass-effect">

        <div class="canvas-wrap">

          <img id="monitorImage" src="http://47.83.199.93:8080/device/stream/rocket001"
               alt="监控画面" style="width:100%;" />

          <div v-if="!connected" class="overlay-status">
            <el-icon class="is-loading">
              <Loading />
            </el-icon>

            <span>等待设备接入...</span>
          </div>

        </div>

      </section>

      <section class="panel panel--info glass-effect">

        <div class="stat-cards">

          <div class="card glass-item">
            <div class="card__num">
              {{ frame.personCount ?? 0 }}
            </div>

            <div class="card__label">
              总人数
            </div>
          </div>

        </div>

        <div class="target-list-wrap">

          <div class="section-title">
            实时识别明细
          </div>

          <div class="drowning-summary">
            <span>溺水人数:</span>
            <span
              :class="{'alarm-active': frame.drowningCount > 0}"
            >
              {{ frame.drowningCount ?? 0 }}
            </span>
          </div>

          <div
            v-if="!frame.targets?.length"
            class="empty"
          >
            无动态目标记录
          </div>

          <table
            v-else
            class="target-table"
          >
            <thead>
              <tr>
                <th>ID</th>
                <th>状态</th>
                <th>位置</th>
              </tr>
            </thead>

            <tbody>
              <tr
                v-for="t in frame.targets"
                :key="t.index"
              >
                <td>#{{ t.index }}</td>

                <td>
                  <span
                    class="badge"
                    :style="{ background: scoreColor(t.score) }"
                  >
                    {{ t.label }}
                  </span>
                </td>

                <td>
                  ({{ t.centerX }}, {{ t.centerY }})
                </td>
              </tr>
            </tbody>
          </table>

        </div>

        <div class="alarm-wrap">

          <div class="section-title">
            报警记录
          </div>

          <div
            v-if="alarms.length === 0"
            class="empty"
          >
            暂无报警记录
          </div>

          <div
            v-for="a in alarms"
            :key="a.id"
            class="alarm-item glass-item"
          >

            <div class="alarm-top">

              <span class="alarm-type">
                {{ a.alarmType }}
              </span>

              <span class="alarm-status">
                {{ a.status }}
              </span>

            </div>

            <div class="alarm-device">
              {{ a.deviceId }}
            </div>

            <div class="alarm-time">
              {{ a.createTime }}
            </div>

          </div>

        </div>

        <div class="history-wrap">

          <div class="section-title">
            检测频率监控
          </div>

          <div class="sparkline-wrap glass-item">

            <svg
              width="100%"
              height="60"
              class="sparkline"
            >
              <polyline
                :points="sparkPoints"
                fill="none"
                stroke="#FF5E00"
                stroke-width="2"
              />

              <circle
                v-for="(p, i) in sparkRaw"
                :key="i"
                :cx="p.x"
                :cy="p.y"
                r="2"
                fill="#fff"
              />
            </svg>

          </div>

        </div>

        <div class="device-list-wrap">
          <div class="device-list-header">
            <div class="section-title no-margin">
              设备列表
            </div>
            <input type="text" class="device-search glass-input" placeholder="搜索设备..." />
          </div>

          <div class="device-item glass-item">
            <div class="device-info-row">
              <span class="device-id-tag">ID: 001</span>
              <span class="device-online-status">
                <i class="status-indicator"></i> 已连接
              </span>
            </div>
            
            <div class="device-detail">
              <div class="detail-line">
                <span class="detail-label">名称:</span>
                <span class="detail-value">rocket001</span>
              </div>
              <div class="detail-line">
                <span class="detail-label">密钥:</span>
                <span class="detail-value mono-text">LB-SEA-0001-ALPHA</span>
              </div>
            </div>
          </div>
        </div>

      </section>

    </main>
  </div>
</template>

<script setup>
import {
  ref,
  computed,
  onMounted,
  onUnmounted
} from 'vue'

import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

import {
  Loading
} from '@element-plus/icons-vue'

import { ElMessage } from 'element-plus'

const WS_URL =
  `${window.location.protocol}//${window.location.host}/ws`

const connected = ref(false)

const currentDeviceId = ref('rocket001') // 默认设备ID

const frame = ref({
  deviceId: null,
  frameNo: null,
  drowningCount: 0,
  personCount: 0,
  callForHelp: 0,
  pressure: 0,
  alarm: 0,
  targets: []
})

const alarms = ref([])

const imgRef = ref(null)

const history = ref([])

const imgTimestamp = ref(Date.now())

let stompClient = null

onMounted(() => {

  fetchAlarmList()

  // 从监控图片中提取 deviceId
  const imgElement = document.getElementById('monitorImage')
  if (imgElement && imgElement.src) {
    try {
      const url = new URL(imgElement.src)
      const pathParts = url.pathname.split('/')
      // 预期路径格式: /device/stream/{deviceId}
      if (pathParts.length >= 4 && pathParts[pathParts.length - 2] === 'stream') {
        currentDeviceId.value = pathParts[pathParts.length - 1]
        console.log('从图片URL中提取到设备ID:', currentDeviceId.value)
      }
    } catch (e) {
      console.error('解析图片URL中的设备ID失败:', e)
    }
  }

  stompClient = new Client({

    webSocketFactory: () => new SockJS(WS_URL),

    reconnectDelay: 3000,

    onConnect: () => {

      connected.value = true
      console.log('WebSocket 已连接')

      // 订阅设备特定的主题
      stompClient.subscribe('/topic/frames/' + currentDeviceId.value, (msg) => {

        const data = JSON.parse(msg.body)
        console.log('收到实时数据:', data)

        frame.value = data

        imgTimestamp.value = Date.now()

        history.value.push({
          frameNo: data.frameNo,
          count: data.personCount ?? 0 // 使用 personCount 作为统计数量
        })

        if (history.value.length > 15) {
          history.value.shift()
        }

      })
      
      // 订阅全局报警主题，用于呼救声弹窗
      stompClient.subscribe('/topic/alarm', (msg) => {
        const alarmData = JSON.parse(msg.body)
        console.log('收到实时报警:', alarmData)
        if (alarmData.type === 'callForHelp') {
          ElMessage.warning({
            dangerouslyUseHTMLString: true, // 允许使用 HTML 字符串
            message: `<div>
                        <p style="margin: 0; font-size: 16px; font-weight: bold;">🚨 呼救声报警！</p>
                        <p style="margin: 5px 0 0; font-size: 14px;">${alarmData.message}</p>
                        <p style="margin: 5px 0 0; font-size: 12px; color: #909399;">${new Date().toLocaleString()}</p>
                      </div>`,
            duration: 0, // 持续时间0，表示不自动关闭，需要手动关闭
            showClose: true, // 显示关闭按钮
            customClass: 'large-alarm-message' // 添加自定义类名
          })
        }
      })

    },

    onDisconnect: () => {
      connected.value = false
      console.log('WebSocket 已断开')
    }
  })

  stompClient.activate()
})

onUnmounted(() => {
  stompClient?.deactivate()
})

function imageUrl(url) {

  if (!url) return ''

  return `${url}?t=${imgTimestamp.value}`
}

async function fetchAlarmList() {

  try {

    const res =
      await fetch('/api/alarm/list')

    alarms.value =
      await res.json()

  } catch (e) {

    console.error(
      '报警列表获取失败',
      e
    )
  }
}

function onImageLoad() {}

function scoreColor(score) {
  return '#10B981'
}

const sparkRaw = computed(() => {

  const list = history.value.slice(-15)

  if (!list.length) {
    return []
  }

  const max =
    Math.max(
      ...list.map(h => h.count),
      1
    )

  const W = 260
  const H = 55
  const PAD = 5

  return list.map((h, i) => ({
    x:
      PAD +
      (i / Math.max(list.length - 1, 1))
      * (W - PAD * 2),

    y:
      H -
      PAD -
      (h.count / max)
      * (H - PAD * 2),
  }))
})

const sparkPoints = computed(() =>
  sparkRaw.value
    .map(p => `${p.x},${p.y}`)
    .join(' ')
)
</script>

<style scoped>
.app {
  position: relative;
  min-height: 100vh;
  background: url('../assets/backgroundImage.png') no-repeat center center;
  background-size: cover;
  color: #fff;
  font-family: 'Inter', system-ui, sans-serif;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.background-overlay {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center,
      rgba(15,23,42,0.2) 0%,
      rgba(15,23,42,0.6) 100%);
  z-index: 0;
}

.glass-effect {
  background: rgba(255,255,255,0.1);
  backdrop-filter: blur(25px);
  border: 1px solid rgba(255,255,255,0.2);
}

.glass-item {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px;
}

.header {
  position: relative;
  z-index: 2;
  padding: 15px 30px;
}

.header-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.title {
  font-size: 20px;
  font-weight: 800;
}

.status-bar {
  display: flex;
  gap: 12px;
  font-size: 12px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot--on {
  background: #10B981;
}

.dot--off {
  background: #EF4444;
}

.main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  gap: 20px;
  padding: 20px;
  overflow-y: auto;
}

.panel {
  border-radius: 24px;
  padding: 20px;
}

.panel--image {
  flex: 2.5;
}

.panel--info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 340px;
}

.canvas-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 16px;
  overflow: hidden;
}

.detection-img,
.detection-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.overlay-status {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.4);
}

.stat-cards {
  display: flex;
}

.card {
  flex: 1;
  padding: 20px;
  text-align: center;
}

.card__num {
  font-size: 32px;
  font-weight: 800;
  color: #FF5E00;
}

.card__label {
  margin-top: 5px;
  font-size: 12px;
}

.section-title {
  margin-bottom: 15px;
  font-size: 13px;
  color: #FF5E00;
  font-weight: 700;
}

.section-title.no-margin {
  margin-bottom: 0;
}

.target-table {
  width: 100%;
  border-collapse: collapse;
}

.target-table th,
.target-table td {
  padding: 12px 10px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
  font-size: 13px;
}

.badge {
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 11px;
}

.alarm-wrap {
  max-height: 260px;
  overflow-y: auto;
}

.alarm-item {
  padding: 14px;
  margin-bottom: 12px;
}

.alarm-top {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.alarm-type {
  color: #FF5E00;
  font-weight: 700;
}

.alarm-status {
  font-size: 12px;
  color: #10B981;
}

.alarm-device {
  font-size: 12px;
  color: rgba(255,255,255,0.7);
  margin-bottom: 5px;
}

.alarm-time {
  font-size: 11px;
  color: rgba(255,255,255,0.5);
}

.empty {
  color: rgba(255,255,255,0.5);
  font-size: 13px;
}

.sparkline-wrap {
  padding: 15px;
}

.drowning-summary {
  margin-top: -5px;
  margin-bottom: 15px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.8);
}

.drowning-summary span:first-child {
  font-weight: bold;
}

.drowning-summary .alarm-active {
  color: #EF4444;
  font-weight: bold;
  animation: pulse-red 1.5s infinite;
}

@keyframes pulse-red {
  0% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.05); opacity: 0.7; }
  100% { transform: scale(1); opacity: 1; }
}

/* 设备列表及搜索框样式 */
.device-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.glass-input {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 6px;
  padding: 6px 10px;
  color: #fff;
  font-size: 12px;
  outline: none;
  width: 130px;
  transition: all 0.3s ease;
}

.glass-input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}

.glass-input:focus {
  border-color: #FF5E00;
  background: rgba(255, 255, 255, 0.1);
  box-shadow: 0 0 5px rgba(255, 94, 0, 0.3);
}

.device-item {
  padding: 15px;
}

.device-info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.device-id-tag {
  background: rgba(255, 94, 0, 0.2);
  color: #FF5E00;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: bold;
}

.device-online-status {
  color: #10B981;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-indicator {
  width: 6px;
  height: 6px;
  background: #10B981;
  border-radius: 50%;
  box-shadow: 0 0 8px #10B981;
}

.device-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-line {
  display: flex;
  font-size: 12px;
}

.detail-label {
  color: rgba(255, 255, 255, 0.5);
  width: 40px;
}

.detail-value {
  color: rgba(255, 255, 255, 0.9);
}

.mono-text {
  font-family: 'Courier New', Courier, monospace;
  letter-spacing: 0.5px;
}

::-webkit-scrollbar {
  width: 4px;
}

::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.2);
}

/* Element Plus 消息弹窗自定义样式 */
:global(.el-message.large-alarm-message) {
  min-width: 380px !important; /* 调整最小宽度 */
  padding: 15px 20px !important; /* 调整内边距 */
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2) !important;
  border-radius: 8px !important;
  border: 1px solid #e6a23c !important; /* 警告色边框 */
  background-color: rgba(253, 246, 236, 0.95) !important; /* 警告色背景 */
}

:global(.el-message.large-alarm-message .el-message__content) {
  font-size: 14px !important; /* 调整内容字体大小 */
  color: #606266 !important;
}

:global(.el-message.large-alarm-message .el-message__closeBtn) {
  top: 18px !important;
  font-size: 16px !important;
  color: #606266 !important;
}
</style>