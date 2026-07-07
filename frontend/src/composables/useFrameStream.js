import { onUnmounted } from 'vue'

export function useFrameStream(deviceId) {
  let ws = null, canvas = null, ctx = null, raf = null
  let bmp = null, pending = null

  function connect() {
    const host = window.location.host
    ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${host}/ws-browser-frame`)
    ws.binaryType = 'arraybuffer'

    ws.onopen = () => {
      ws.send(deviceId.value)
      if (!canvas) {
        canvas = document.createElement('canvas')
        canvas.style.cssText = 'width:100%;height:100%;object-fit:cover;border-radius:16px;display:block;'
        const c = document.getElementById('frameContainer')
        if (c) { c.innerHTML = ''; c.appendChild(canvas); ctx = canvas.getContext('2d') }
      }
      start()
    }

    // 收到帧 → 解码 → 替换 pending
    ws.onmessage = (e) => {
      createImageBitmap(new Blob([e.data], { type: 'image/jpeg' }))
        .then(b => { if (pending) pending.close(); pending = b })
        .catch(() => {})
    }

    ws.onclose = () => { stop(); setTimeout(connect, 500) }
  }

  function start() {
    const render = () => {
      raf = requestAnimationFrame(render)
      if (!ctx) return

      if (pending) {
        if (bmp) bmp.close()
        bmp = pending
        pending = null
        // 只在尺寸变化时重建画布，避免每帧清空底层缓冲区
        if (canvas.width !== bmp.width || canvas.height !== bmp.height) {
          canvas.width = bmp.width
          canvas.height = bmp.height
        }
      }

      if (bmp) ctx.drawImage(bmp, 0, 0)
    }
    raf = requestAnimationFrame(render)
  }

  function stop() {
    if (raf) { cancelAnimationFrame(raf); raf = null }
    if (bmp) { bmp.close(); bmp = null }
    if (pending) { pending.close(); pending = null }
  }

  function disconnect() { if (ws) { ws.onclose = null; ws.close(); ws = null }; stop() }
  onUnmounted(disconnect)
  return { frameUrl: null, connect, disconnect }
}
