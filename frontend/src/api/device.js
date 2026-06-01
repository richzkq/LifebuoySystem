/**
 * 设备数据 API
 */
import http from './http'
import { API, getStreamUrl as buildStreamUrl, getSnapshotUrl as buildSnapshotUrl } from '@/config'

/**
 * 获取设备最新状态（用于轮询）
 */
export function getLatest(deviceId) {
  return http.get(API.DEVICE_LATEST, { params: { deviceId } })
}

/**
 * 获取 MJPEG 流地址（用于 <img> src）
 */
export function getStreamUrl(deviceId) {
  const base = import.meta.env.VITE_API_BASE_URL || ''
  return buildStreamUrl(base, deviceId)
}

/**
 * 获取快照地址
 */
export function getSnapshotUrl(deviceId) {
  const base = import.meta.env.VITE_API_BASE_URL || ''
  return buildSnapshotUrl(base, deviceId)
}
