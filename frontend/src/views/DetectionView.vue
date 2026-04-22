<template>
  <div class="app">
    <header class="header">
      <span class="title">🛟 救生圈检测监控</span>
      <div class="status-bar">
        <span class="dot" :class="connected ? 'dot--on' : 'dot--off'" />
        <span>{{ connected ? '已连接' : '未连接' }}</span>
        <span class="divider">|</span>
        <span>设备：{{ frame.deviceId || '—' }}</span>
        <span class="divider">|</span>
        <span>帧号：{{ frame.frameNo ?? '—' }}</span>
      </div>
    </header>

    <main class="main">
      <!-- ── 左：图像 + BBox 叠层 ── -->
      <section class="panel panel--image">
        <div class="canvas-wrap" ref="wrapRef">
          <!-- 原始图像 -->
          <img
              v-if="frame.imageBase64"
              :src="frame.imageBase64"
              class="detection-img"
              ref="imgRef"
              @load="onImageLoad"
          />
          <div v-else class="placeholder">等待图像…</div>

          <!-- SVG BBox 叠层（跟随图像尺寸缩放） -->
          <svg
              v-if="frame.imageBase64"
              class="bbox-svg"
              :viewBox="`0 0 ${naturalW} ${naturalH}`"
              preserveAspectRatio="none"
          >
            <g v-for="t in frame.targets" :key="t.index">
              <rect
                  :x="t.x1" :y="t.y1"
                  :width="t.x2 - t.x1" :height="t.y2 - t.y1"
                  :stroke="scoreColor(t.score)"
                  stroke-width="4"
                  fill="none"
                  rx="3"
              />
              <!-- 标签背景 -->
              <rect
                  :x="t.x1" :y="t.y1 - 26"
                  :width="labelWidth(t)" height="26"
                  :fill="scoreColor(t.score)"
                  rx="3"
              />
              <text
                  :x="t.x1 + 5" :y="t.y1 - 7"
                  font-size="20" fill="white" font-family="monospace"
              >{{ t.label }} {{ (t.score * 100).toFixed(1) }}%</text>
            </g>
          </svg>
        </div>
      </section>

      <!-- ── 右：目标列表 + 统计 ── -->
      <section class="panel panel--info">
        <div class="stat-cards">
          <div class="card">
            <div class="card__num">{{ frame.detectCount ?? 0 }}</div>
            <div class="card__label">本帧目标数</div>
          </div>
          <div class="card">
            <div class="card__num">{{ totalDetected }}</div>
            <div class="card__label">累计检测目标</div>
          </div>
          <div class="card">
            <div class="card__num">{{ frameCount }}</div>
            <div class="card__label">已接收帧数</div>
          </div>
        </div>

        <div class="target-list-wrap">
          <div class="section-title">目标明细</div>
          <div v-if="!frame.targets?.length" class="empty">本帧无目标</div>
          <table v-else class="target-table">
            <thead>
            <tr>
              <th>#</th>
              <th>标签</th>
              <th>置信度</th>
              <th>坐标</th>
            </tr>
            </thead>
            <tbody>
            <tr v-for="t in frame.targets" :key="t.index">
              <td>{{ t.index }}</td>
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
              <td class="coord">
                ({{ t.x1 }},{{ t.y1 }})–({{ t.x2 }},{{ t.y2 }})
              </td>
            </tr>
            </tbody>
          </table>
        </div>

        <!-- 最近帧历史（最近 10 帧的目标数折线图） -->
        <div class="history-wrap">
          <div class="section-title">最近帧目标数</div>
          <div class="sparkline-wrap">
            <svg width="100%" height="60" class="sparkline">
              <polyline
                  :points="sparkPoints"
                  fill="none"
                  stroke="#4ade80"
                  stroke-width="2"
              />
              <circle
                  v-for="(p, i) in sparkRaw" :key="i"
                  :cx="p.x" :cy="p.y" r="3"
                  fill="#4ade80"
              />
            </svg>
            <div class="sparkline-labels">
              <span v-for="h in history.slice(-10)" :key="h.frameNo" class="sl-label">
                {{ h.count }}
              </span>
            </div>
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

// ── WebSocket 连接配置 ──────────────────────────────────────
const WS_URL = 'http://192.168.1.88:8080/ws'  // 对应 application.yml server.port

const connected  = ref(false)
const frame      = ref({ targets: [] })
const naturalW   = ref(960)
const naturalH   = ref(540)
const imgRef     = ref(null)
const history    = ref([])      // [{ frameNo, count }]
const frameCount = ref(0)
const totalDetected = ref(0)

let stompClient = null

onMounted(() => {
  stompClient = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 3000,
    onConnect: () => {
      connected.value = true
      // 订阅后端推送的帧数据
      stompClient.subscribe('/topic/frames', (msg) => {
        const data = JSON.parse(msg.body)
        frame.value = data
        frameCount.value++
        totalDetected.value += data.detectCount ?? 0

        // 保留最近 10 帧历史
        history.value.push({ frameNo: data.frameNo, count: data.detectCount ?? 0 })
        if (history.value.length > 10) history.value.shift()
      })
    },
    onDisconnect: () => { connected.value = false },
    onStompError:  () => { connected.value = false },
  })
  stompClient.activate()
})

onUnmounted(() => { stompClient?.deactivate() })

// ── 图像加载后记录原始尺寸（用于 SVG viewBox）──────────────
function onImageLoad () {
  naturalW.value = imgRef.value?.naturalWidth  || 960
  naturalH.value = imgRef.value?.naturalHeight || 540
}

// ── 置信度 → 颜色（绿 / 橙 / 红）──────────────────────────
function scoreColor (score) {
  if (score >= 0.7) return '#22c55e'
  if (score >= 0.4) return '#f97316'
  return '#ef4444'
}

// 标签背景宽度估算（每字符约 13px + padding）
function labelWidth (t) {
  const text = `${t.label} ${(t.score * 100).toFixed(1)}%`
  return text.length * 13 + 10
}

// ── Sparkline 折线图计算 ────────────────────────────────────
const sparkRaw = computed(() => {
  const list = history.value.slice(-10)
  if (!list.length) return []
  const max = Math.max(...list.map(h => h.count), 1)
  const W = 300, H = 55, PAD = 5
  return list.map((h, i) => ({
    x: PAD + (i / Math.max(list.length - 1, 1)) * (W - PAD * 2),
    y: H - PAD - (h.count / max) * (H - PAD * 2),
  }))
})

const sparkPoints = computed(() =>
    sparkRaw.value.map(p => `${p.x},${p.y}`).join(' ')
)
</script>

<style scoped>
/* ── 全局 ── */
.app {
  min-height: 100vh;
  background: #0f172a;
  color: #e2e8f0;
  font-family: 'Segoe UI', system-ui, sans-serif;
  display: flex;
  flex-direction: column;
}

/* ── Header ── */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: #1e293b;
  border-bottom: 1px solid #334155;
}
.title { font-size: 18px; font-weight: 700; letter-spacing: .5px; }
.status-bar { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #94a3b8; }
.divider { color: #475569; }
.dot { width: 9px; height: 9px; border-radius: 50%; }
.dot--on  { background: #22c55e; box-shadow: 0 0 6px #22c55e; }
.dot--off { background: #475569; }

/* ── Main layout ── */
.main {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px;
  overflow: hidden;
}

/* ── Panels ── */
.panel {
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 12px;
  padding: 16px;
  overflow: auto;
}
.panel--image { flex: 2; display: flex; align-items: center; justify-content: center; }
.panel--info  { flex: 1; display: flex; flex-direction: column; gap: 16px; min-width: 300px; }

/* ── Canvas / image wrap ── */
.canvas-wrap {
  position: relative;
  width: 100%;
  max-height: calc(100vh - 120px);
  display: flex;
  align-items: center;
  justify-content: center;
}
.detection-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: 8px;
  display: block;
}
.bbox-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.placeholder {
  color: #475569;
  font-size: 15px;
  padding: 80px 0;
}

/* ── Stat cards ── */
.stat-cards { display: flex; gap: 10px; }
.card {
  flex: 1;
  background: #0f172a;
  border: 1px solid #334155;
  border-radius: 10px;
  padding: 12px;
  text-align: center;
}
.card__num   { font-size: 28px; font-weight: 700; color: #4ade80; }
.card__label { font-size: 11px; color: #64748b; margin-top: 4px; }

/* ── Section title ── */
.section-title { font-size: 12px; color: #64748b; letter-spacing: 1px; text-transform: uppercase; margin-bottom: 8px; }

/* ── Target table ── */
.target-list-wrap { flex: 1; overflow: auto; }
.empty { color: #475569; font-size: 13px; text-align: center; padding: 24px 0; }
.target-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.target-table th {
  text-align: left;
  color: #64748b;
  font-weight: 600;
  padding: 6px 8px;
  border-bottom: 1px solid #334155;
  white-space: nowrap;
}
.target-table td { padding: 6px 8px; border-bottom: 1px solid #1e293b; vertical-align: middle; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 99px; font-size: 12px; color: #fff; }
.coord { font-family: monospace; font-size: 11px; color: #94a3b8; white-space: nowrap; }

/* ── Score bar ── */
.score-bar-wrap { display: flex; align-items: center; gap: 6px; }
.score-bar { height: 6px; border-radius: 3px; transition: width .3s; }
.score-bar-wrap span { font-size: 11px; white-space: nowrap; }

/* ── Sparkline ── */
.history-wrap { margin-top: auto; }
.sparkline-wrap { background: #0f172a; border-radius: 8px; padding: 8px; }
.sparkline { overflow: visible; }
.sparkline-labels { display: flex; justify-content: space-between; margin-top: 4px; }
.sl-label { font-size: 10px; color: #475569; text-align: center; flex: 1; }
</style>