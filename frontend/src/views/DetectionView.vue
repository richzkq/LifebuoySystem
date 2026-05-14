<template>
  <div class="app">
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
        </div>
      </div>
    </header>

    <main class="main">

      <section class="panel panel--image glass-effect">

        <div class="canvas-wrap">

          <img
              v-if="connected && frame.imageUrl"
              :src="imageUrl(frame.imageUrl)"
              class="detection-img"
              ref="imgRef"
              @load="onImageLoad"
          />

          <video
              v-else
              src="/video.mp4"
              class="detection-video"
              autoplay
              loop
              muted
              playsinline
          ></video>

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
              {{ frame.detectCount ?? 0 }}
            </div>

            <div class="card__label">
              当前目标
            </div>
          </div>

        </div>

        <div class="target-list-wrap">

          <div class="section-title">
            实时识别明细
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
                  fill="#FF5E00"
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

const WS_URL =
    `${window.location.protocol}//${window.location.host}/ws`

const connected = ref(false)

const frame = ref({
  targets: []
})

const alarms = ref([])

const imgRef = ref(null)

const history = ref([])

const imgTimestamp = ref(Date.now())

let stompClient = null

onMounted(() => {

  fetchAlarmList()

  stompClient = new Client({

    webSocketFactory: () => new SockJS(WS_URL),

    reconnectDelay: 3000,

    onConnect: () => {

      connected.value = true

      stompClient.subscribe('/topic/frames', (msg) => {

        const data = JSON.parse(msg.body)

        frame.value = data

        imgTimestamp.value = Date.now()

        history.value.push({
          frameNo: data.frameNo,
          count: data.detectCount ?? 0
        })

        if (history.value.length > 15) {
          history.value.shift()
        }

      })
    },

    onDisconnect: () => {
      connected.value = false
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
  /* 移除图片，改为纯白背景 */
  background-color: #ffffff;
  /* 字体颜色改为深色，适配白底 */
  color: #333333;
  font-family: 'Inter', system-ui, sans-serif;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 调整玻璃拟态的颜色为深色半透明，以适配白色背景 */
.glass-effect {
  background: rgba(0, 0, 0, 0.03);
  backdrop-filter: blur(25px);
  border: 1px solid rgba(0, 0, 0, 0.1);
}

.glass-item {
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid rgba(0, 0, 0, 0.08);
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
  background: rgba(255, 255, 255, 0.7); /* 调整了 loading 遮罩的颜色 */
  color: #333;
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
  border-bottom: 1px solid rgba(0, 0, 0, 0.08); /* 表格边框改为深色透明 */
  font-size: 13px;
}

.badge {
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: #fff; /* 徽章内的文字保持白色 */
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
  color: rgba(0, 0, 0, 0.6); /* 改为深色半透明 */
  margin-bottom: 5px;
}

.alarm-time {
  font-size: 11px;
  color: rgba(0, 0, 0, 0.4); /* 改为深色半透明 */
}

.empty {
  color: rgba(0, 0, 0, 0.4); /* 改为深色半透明 */
  font-size: 13px;
}

.sparkline-wrap {
  padding: 15px;
}

/* 设备列表及搜索框样式 */
.device-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.glass-input {
  background: rgba(0, 0, 0, 0.03); /* 输入框背景 */
  border: 1px solid rgba(0, 0, 0, 0.15); /* 输入框边框 */
  border-radius: 6px;
  padding: 6px 10px;
  color: #333; /* 输入框文字颜色 */
  font-size: 12px;
  outline: none;
  width: 130px;
  transition: all 0.3s ease;
}

.glass-input::placeholder {
  color: rgba(0, 0, 0, 0.3); /* 占位符颜色 */
}

.glass-input:focus {
  border-color: #FF5E00;
  background: #ffffff;
  box-shadow: 0 0 5px rgba(255, 94, 0, 0.2);
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
  background: rgba(255, 94, 0, 0.1);
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
  box-shadow: 0 0 6px rgba(16, 185, 129, 0.6);
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
  color: rgba(0, 0, 0, 0.5); /* 改为深色半透明 */
  width: 40px;
}

.detail-value {
  color: rgba(0, 0, 0, 0.8); /* 改为深色 */
}

.mono-text {
  font-family: 'Courier New', Courier, monospace;
  letter-spacing: 0.5px;
}

::-webkit-scrollbar {
  width: 4px;
}

::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.2); /* 滚动条适配浅色模式 */
  border-radius: 4px;
}
</style>