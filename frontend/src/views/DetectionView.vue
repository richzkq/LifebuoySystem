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

          <span class="divider">|</span>

          <span>
            温度: <span :style="{ color: frame.temperature > 60 ? '#EF4444' : frame.temperature > 45 ? '#F59E0B' : '#10B981' }">{{ frame.temperature ?? '—' }}°C</span>
          </span>

          <span class="divider">|</span>

          <button class="logout-btn" @click="handleLogout">退出</button>
        </div>
      </div>
    </header>

    <main class="main">

      <section class="panel panel--image glass-effect">

        <div class="canvas-wrap">

          <img id="monitorImage" :src="frameUrl"
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
            <div class="card__num" :class="{ 'card__num--alarm': frame.drowningCount > 0 }">
              {{ frame.drowningCount ?? 0 }}
            </div>

            <div class="card__label">
              溺水人数
            </div>
          </div>

        </div>

        <div class="alarm-wrap">

          <div class="section-title">
            报警记录
          </div>

          <div
            v-if="alarmLoading"
            class="empty"
          >
            加载中...
          </div>

          <div
            v-else-if="alarms.length === 0"
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

        <div class="device-list-wrap">
          <div class="device-list-header">
            <div class="section-title no-margin">
              设备列表
            </div>
            <input type="text" class="device-search glass-input" placeholder="搜索设备..." />
          </div>

          <div class="device-item glass-item">
            <div class="device-info-row">
              <span class="device-id-tag">{{ deviceId }}</span>
              <span class="device-online-status">
                <i class="status-indicator"></i> {{ connected ? '已连接' : '未连接' }}
              </span>
            </div>
          </div>
        </div>

      </section>

    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useWebSocket } from '@/composables/useWebSocket'
import { getAlarmList } from '@/api/alarm'
import { useFrameStream } from '@/composables/useFrameStream'
import { DEFAULT_DEVICE_ID } from '@/config'

const router = useRouter()
const authStore = useAuthStore()

// ============ 设备 ID ============
const deviceId = ref(DEFAULT_DEVICE_ID)

// ============ WebSocket 实时数据 ============
const { connected, frame, connect } = useWebSocket(deviceId)

// ============ 浏览器直连帧推送 (替代 MJPEG，零延迟) ============
const { frameUrl, connect: connectFrameStream } = useFrameStream(deviceId)

// ============ 报警记录 ============
const alarms = ref([])
const alarmLoading = ref(false)

async function fetchAlarmList() {
  alarmLoading.value = true
  try {
    const res = await getAlarmList()
    alarms.value = res.data
  } catch (e) {
    console.error('报警列表获取失败', e)
    ElMessage.error('报警列表加载失败')
  } finally {
    alarmLoading.value = false
  }
}

// ============ 退出登录 ============
function handleLogout() {
  authStore.logout()
  router.replace('/login')
}

// ============ 生命周期 ============
onMounted(() => {
  fetchAlarmList()
  connect()
  connectFrameStream()
})
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
  align-items: center;
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

.logout-btn {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: rgba(255, 255, 255, 0.7);
  padding: 4px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
}
.logout-btn:hover {
  background: rgba(239, 68, 68, 0.3);
  color: #EF4444;
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
  color: #10B981;
  transition: color 0.3s;
}
.card__num--alarm {
  color: #EF4444;
  animation: pulse-red 1.5s infinite;
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







/* ==========================================
   🛡️ 智能救生圈大屏：磨砂玻璃提警弹窗（全局强制生效版）
   ========================================== */

/* 1. 精致的毛玻璃边缘泛红呼吸灯特效 */
@keyframes glass-emergency-pulse {
  0% {
    box-shadow: 0 8px 32px 0 rgba(239, 68, 68, 0.25), inset 0 0 0 1px rgba(255, 255, 255, 0.1);
    border-color: rgba(255, 255, 255, 0.25) !important;
  }
  50% {
    box-shadow: 0 8px 32px 0 rgba(239, 68, 68, 0.6), inset 0 0 20px rgba(239, 68, 68, 0.25);
    border-color: rgba(239, 68, 68, 0.7) !important;
  }
  100% {
    box-shadow: 0 8px 32px 0 rgba(239, 68, 68, 0.25), inset 0 0 0 1px rgba(255, 255, 255, 0.1);
    border-color: rgba(255, 255, 255, 0.25) !important;
  }
}

@keyframes glass-red-dot {
  0%, 100% { opacity: 0.4; transform: scale(0.9); }
  50% { opacity: 1; transform: scale(1.2); box-shadow: 0 0 10px #EF4444; }
}

/* 2. 强制剥离 Element 默认的粉红、红字、内边距，强制注入磨砂玻璃 */
.el-message.glass-alarm-message {
  background-color: rgba(255, 255, 255, 0.05) !important; /* 超低饱和度白，实现高级透明 */
  background-image: none !important;
  backdrop-filter: blur(20px) !important;                /* 磨砂玻璃的核心滤镜 */
  -webkit-backdrop-filter: blur(20px) !important;

  border: 1px solid rgba(255, 255, 255, 0.25) !important; /* 微细白边框 */
  border-radius: 12px !important;
  padding: 16px 20px !important;
  width: 320px !important;
  min-width: 320px !important;
  box-sizing: border-box !important;

  /* 挂载动画与基础排版 */
  animation: glass-emergency-pulse 2.5s infinite ease-in-out !important;
  display: flex !important;
  align-items: flex-start !important;
}

/* 3. 清理 Element 自带的干扰布局和隐藏默认危险小红标 */
.glass-alarm-message .el-message__icon {
  display: none !important;
}
.glass-alarm-message .el-message__content {
  padding: 0 !important;
  width: 100% !important;
  color: inherit !important;
}

/* 4. 微调自带的关闭按钮 */
.glass-alarm-message .el-message__closeBtn {
  color: rgba(255, 255, 255, 0.6) !important;
  font-size: 16px !important;
  position: absolute !important;
  top: 14px !important;
  right: 14px !important;
}
.glass-alarm-message .el-message__closeBtn:hover {
  color: #EF4444 !important;
}

/* ─── 5. 内部盒子极简排版 ─── */
.glass-alarm-box {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px; /* 强制撑开高度，防止图文任何形式的重叠 */
  text-align: left;
}

/* 标题栏 */
.glass-alarm-header {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1;
}

/* 纳米级警示闪烁红点 */
.glass-alarm-dot {
  width: 8px;
  height: 8px;
  background-color: #EF4444 !important;
  border-radius: 50%;
  display: inline-block;
  animation: glass-red-dot 1.2s infinite ease-in-out;
}

.glass-alarm-title {
  font-size: 16px;
  font-weight: bold;
  color: #EF4444 !important; /* 纯正亮红色 */
  letter-spacing: 0.5px;
}

/* 设备 ID 内容区 */
.glass-alarm-content {
  font-size: 15px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.95) !important; /* 纯净白色，彻底甩开恶心的重叠 */
  padding-left: 16px;
  line-height: 1.2;
}

/* 底部右下角极简半透明时间 */
.glass-alarm-time {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4) !important;
  text-align: right;
  width: 100%;
  margin-top: 2px;
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
