<template>
  <div class="app">
    <div class="background-overlay"></div>

    <header class="header">
      <div class="header-content">
        <span class="title">🛟 SMART LIFEBUOY 实时监控</span>
        <div class="status-bar">
          <span class="dot" :class="connected ? 'dot--on' : 'dot--off'" />
          <span>{{ connected ? 'SERVER ONLINE' : 'DISCONNECTED' }}</span>
          <span class="divider">|</span>
          <span>设备: {{ frame.deviceId || '—' }}</span>
          <span class="divider">|</span>
          <span>帧率: {{ frame.frameNo ?? '—' }}</span>
        </div>
      </div>
    </header>

    <main class="main">
      <section class="panel panel--image glass-effect">
        <div class="canvas-wrap" ref="wrapRef">
          <img
              v-if="frame.imageUrl"
              :src="imageUrl(frame.imageUrl)"
              class="detection-img"
              ref="imgRef"
              @load="onImageLoad"
          />
          <div v-else class="placeholder">
            <el-icon class="is-loading"><Loading /></el-icon>
            <p>等待视频流连接...</p>
          </div>
        </div>
      </section>

      <section class="panel panel--info glass-effect">
        <div class="stat-cards">
          <div class="card glass-item">
            <div class="card__num">{{ frame.detectCount ?? 0 }}</div>
            <div class="card__label">当前目标</div>
          </div>
          <div class="card glass-item">
            <div class="card__num">{{ totalDetected }}</div>
            <div class="card__label">累计预警</div>
          </div>
        </div>

        <div class="target-list-wrap">
          <div class="section-title">实时识别明细</div>
          <div v-if="!frame.targets?.length" class="empty">无动态目标记录</div>
          <table v-else class="target-table">
            <thead>
            <tr>
              <th>ID</th>
              <th>状态</th>
              <th>置信度</th>
              <th>位置</th>
            </tr>
            </thead>
            <tbody>
            <tr v-for="t in frame.targets" :key="t.index">
              <td>#{{ t.index }}</td>
              <td>
                  <span class="badge" :style="{ background: scoreColor(t.score) }">
                    {{ t.label }}
                  </span>
              </td>
              <td>
                <div class="score-bar-wrap">
                  <div class="score-bar" :style="{ width: (t.score * 100) + '%', background: scoreColor(t.score) }" />
                  <span>{{ (t.score * 100).toFixed(1) }}%</span>
                </div>
              </td>
              <td>({{ t.centerX }}, {{ t.centerY }})</td>
            </tr>
            </tbody>
          </table>
        </div>

        <div class="history-wrap">
          <div class="section-title">检测频率监控</div>
          <div class="sparkline-wrap glass-item">
            <svg width="100%" height="60" class="sparkline">
              <polyline
                  :points="sparkPoints"
                  fill="none"
                  stroke="#FF5E00"
                  stroke-width="2"
              />
              <circle
                  v-for="(p, i) in sparkRaw" :key="i"
                  :cx="p.x" :cy="p.y" r="2"
                  fill="#fff"
              />
            </svg>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { Loading } from '@element-plus/icons-vue'

const WS_URL = `${window.location.protocol}//${window.location.host}/ws`

const connected     = ref(false)
const frame         = ref({ targets: [] })
const naturalW      = ref(960)
const naturalH      = ref(540)
const imgRef        = ref(null)
const history       = ref([])
const frameCount    = ref(0)
const totalDetected = ref(0)
const imgTimestamp  = ref(Date.now())

let stompClient = null

onMounted(() => {
  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 3000,
    onConnect: () => {
      connected.value = true
      stompClient.subscribe('/topic/frames', (msg) => {
        const data = JSON.parse(msg.body)
        frame.value = data
        imgTimestamp.value = Date.now()
        frameCount.value++
        totalDetected.value += data.detectCount ?? 0
        history.value.push({ frameNo: data.frameNo, count: data.detectCount ?? 0 })
        if (history.value.length > 15) history.value.shift()
      })
    },
    onDisconnect: () => { connected.value = false },
  })
  stompClient.activate()
})

onUnmounted(() => { stompClient?.deactivate() })

// 走 vite 代理转发到 8080，相对路径加时间戳防缓存
function imageUrl(url) {
  if (!url) return ''
  return `${url}?t=${imgTimestamp.value}`
}

function onImageLoad() {
  naturalW.value = imgRef.value?.naturalWidth  || 960
  naturalH.value = imgRef.value?.naturalHeight || 540
}

function scoreColor(score) {
  if (score >= 0.7) return '#10B981'
  if (score >= 0.4) return '#FF5E00'
  return '#EF4444'
}

const sparkRaw = computed(() => {
  const list = history.value.slice(-15)
  if (!list.length) return []
  const max = Math.max(...list.map(h => h.count), 1)
  const W = 260, H = 55, PAD = 5
  return list.map((h, i) => ({
    x: PAD + (i / Math.max(list.length - 1, 1)) * (W - PAD * 2),
    y: H - PAD - (h.count / max) * (H - PAD * 2),
  }))
})

const sparkPoints = computed(() => sparkRaw.value.map(p => `${p.x},${p.y}`).join(' '))
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
  background: radial-gradient(circle at center, rgba(15, 23, 42, 0.2) 0%, rgba(15, 23, 42, 0.6) 100%);
  z-index: 0;
}

.glass-effect {
  background: rgba(255, 255, 255, 0.1) !important;
  backdrop-filter: blur(25px) saturate(150%);
  -webkit-backdrop-filter: blur(25px) saturate(150%);
  border: 1px solid rgba(255, 255, 255, 0.2) !important;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.glass-item {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}

.header {
  position: relative;
  z-index: 2;
  padding: 15px 30px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.header-content {
  max-width: 1600px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.title {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 1px;
  text-shadow: 0 2px 4px rgba(0,0,0,0.3);
}
.status-bar { display: flex; align-items: center; gap: 12px; font-size: 12px; color: rgba(255,255,255,0.7); }
.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot--on  { background: #10B981; box-shadow: 0 0 10px #10B981; }
.dot--off { background: #EF4444; }

.main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  gap: 20px;
  padding: 20px;
  max-width: 1600px;
  margin: 0 auto;
  width: 100%;
}

.panel {
  border-radius: 24px;
  padding: 20px;
}
.panel--image { flex: 2.5; display: flex; flex-direction: column; }
.panel--info  { flex: 1; display: flex; flex-direction: column; gap: 20px; min-width: 340px; }

.canvas-wrap {
  position: relative;
  width: 100%;
  flex: 1;
  background: rgba(0,0,0,0.2);
  border-radius: 16px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.detection-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.placeholder {
  text-align: center;
  color: rgba(255,255,255,0.4);
}
.placeholder p { margin-top: 10px; font-size: 14px; }

.stat-cards { display: flex; gap: 15px; }
.card {
  flex: 1;
  padding: 20px 10px;
  text-align: center;
  transition: transform 0.3s;
}
.card:hover { transform: translateY(-5px); }
.card__num   { font-size: 32px; font-weight: 800; color: #FF5E00; }
.card__label { font-size: 11px; color: rgba(255,255,255,0.5); text-transform: uppercase; margin-top: 5px; }

.section-title {
  font-size: 12px;
  color: #FF5E00;
  font-weight: 700;
  margin-bottom: 15px;
  letter-spacing: 1px;
}
.target-table { width: 100%; border-collapse: collapse; }
.target-table th {
  text-align: left;
  font-size: 11px;
  color: rgba(255,255,255,0.4);
  padding: 10px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
.target-table td { padding: 12px 10px; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 13px; }
.badge { padding: 2px 10px; border-radius: 6px; font-size: 11px; font-weight: bold; }

.score-bar-wrap { display: flex; align-items: center; gap: 8px; }
.score-bar { height: 4px; border-radius: 2px; }
.score-bar-wrap span { font-size: 10px; color: rgba(255,255,255,0.6); min-width: 35px; }

.sparkline-wrap {
  padding: 15px;
  height: 80px;
  display: flex;
  align-items: center;
}
.sparkline { overflow: visible; }

::-webkit-scrollbar { width: 4px; }
::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 10px; }
</style>