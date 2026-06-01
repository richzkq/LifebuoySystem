/**
 * 应用全局常量配置
 */

// API 端点
export const API = {
  // 用户认证
  USER_LOGIN: '/user/login',
  USER_INFO: '/user/info',

  // 报警记录
  ALARM_LIST: '/api/alarm/list',

  // 设备数据
  DEVICE_UPLOAD: '/device/upload',
  DEVICE_LATEST: '/device/latest',
  DEVICE_FRAME: '/device/frame',
}

/**
 * 根据 deviceId 生成流地址（用于 <img> MJPEG）
 */
export function getStreamUrl(serverBaseUrl, deviceId) {
  return `${serverBaseUrl}/device/stream/${deviceId}`
}

/**
 * 根据 deviceId 生成快照地址
 */
export function getSnapshotUrl(serverBaseUrl, deviceId) {
  return `${serverBaseUrl}/device/snapshot/${deviceId}`
}

// WebSocket STOMP 主题
export const WS_TOPIC = {
  FRAMES: '/topic/frames',
  ALARM: '/topic/alarm',
}

// 默认设备
export const DEFAULT_DEVICE_ID = import.meta.env.VITE_DEFAULT_DEVICE_ID || 'rocket001'

// 本地存储键
export const STORAGE_KEY = {
  TOKEN: 'token',
}

// UI 常量
export const UI = {
  HISTORY_MAX: 15,            // 折线图保留最近 N 个数据点
  CALL_HELP_LATCH_MS: 30000,  // 呼救锁存时间
  WS_RECONNECT_DELAY: 3000,   // WebSocket 重连间隔
  AXIOS_TIMEOUT: 5000,        // HTTP 请求超时
}
