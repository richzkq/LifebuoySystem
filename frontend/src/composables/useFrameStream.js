/**
 * 浏览器直连帧推送 — 替代 MJPEG，服务器收到帧后直接推给浏览器
 * 延迟：RK3588 → 浏览器，零中间缓冲
 */
import { ref, onUnmounted } from 'vue'

export function useFrameStream(deviceId) {
  const frameUrl = ref('')
  let ws = null

  function connect() {
    const url = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws-browser-frame`
    ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'

    ws.onopen = () => {
      // 首条消息告诉服务器要订阅的设备
      ws.send(deviceId.value)
    }

    ws.onmessage = (event) => {
      // 收到原始 JPEG 字节 → 转 blob URL → 更新 img
      const blob = new Blob([event.data], { type: 'image/jpeg' })
      const url = URL.createObjectURL(blob)
      // 释放旧 URL 防止内存泄漏
      if (frameUrl.value) {
        URL.revokeObjectURL(frameUrl.value)
      }
      frameUrl.value = url
    }

    ws.onclose = () => {
      // 断线重连
      setTimeout(connect, 1000)
    }
  }

  function disconnect() {
    if (ws) {
      ws.onclose = null // 阻止自动重连
      ws.close()
      ws = null
    }
    if (frameUrl.value) {
      URL.revokeObjectURL(frameUrl.value)
      frameUrl.value = ''
    }
  }

  onUnmounted(disconnect)

  return { frameUrl, connect, disconnect }
}
