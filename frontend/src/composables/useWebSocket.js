/**
 * WebSocket 实时数据订阅 (STOMP over SockJS)
 *
 * @param {import('vue').Ref<string>} deviceId - 当前设备 ID
 * @returns {{ connected, frame, onAlarm, disconnect }}
 */
import { ref, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { ElMessage } from 'element-plus'
import { WS_TOPIC, UI } from '@/config'

export function useWebSocket(deviceId) {
  const connected = ref(false)
  const frame = ref({
    deviceId: null,
    frameNo: null,
    drowningCount: 0,
    personCount: 0,
    callForHelp: 0,
    pressure: 0,
    alarm: 0,
    targets: [],
  })

  let stompClient = null
  let lastPressure = 0

  const wsUrl = `${window.location.protocol}//${window.location.host}/ws`

  function connect() {
    stompClient = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: UI.WS_RECONNECT_DELAY,

      onConnect: () => {
        connected.value = true
        console.log('WebSocket 已连接')

        // 订阅设备帧数据
        stompClient.subscribe(
          `${WS_TOPIC.FRAMES}/${deviceId.value}`,
          (msg) => {
            try {
              const data = JSON.parse(msg.body)
              frame.value = data

              if (data.pressure === 1 && lastPressure !== 1) {
                ElMessage.success({
                  message: '救援结束',
                  duration: 3000,
                  showClose: true,
                })
              }
              lastPressure = data.pressure ?? 0
            } catch (e) {
              console.error('WebSocket 数据解析失败:', e)
            }
          }
        )

        // 订阅全局报警
        stompClient.subscribe(WS_TOPIC.ALARM, (msg) => {
          try {
            const alarmData = JSON.parse(msg.body)
            if (alarmData.type === 'drowningAlarm') {
              ElMessage.error({
                dangerouslyUseHTMLString: true,
                message: `
                  <div class="glass-alarm-box">
                    <div class="glass-alarm-header">
                      <span class="glass-alarm-dot"></span>
                      <span class="glass-alarm-title">🛟 溺水报警</span>
                    </div>
                    <div class="glass-alarm-content">
                      ${alarmData.deviceId || ''} — ${alarmData.message || '检测到溺水!'}
                    </div>
                    <div class="glass-alarm-time">
                      ${new Date().toLocaleTimeString()}
                    </div>
                  </div>
                `,
                duration: 0,
                showClose: true,
                customClass: 'glass-alarm-message',
              })
            }

            if (alarmData.type === 'callForHelp') {
              ElMessage.error({
                dangerouslyUseHTMLString: true,
                message: `
                  <div class="glass-alarm-box">
                    <div class="glass-alarm-header">
                      <span class="glass-alarm-dot"></span>
                      <span class="glass-alarm-title">⚠️ 呼救报警</span>
                    </div>
                    <div class="glass-alarm-content">
                      ${alarmData.message || '未知设备触发'}
                    </div>
                    <div class="glass-alarm-time">
                      ${new Date().toLocaleTimeString()}
                    </div>
                  </div>
                `,
                duration: 0,
                showClose: true,
                customClass: 'glass-alarm-message',
              })
            }

            if (alarmData.type === 'servoTrigger') {
              ElMessage.warning({
                message: `🛟 ${alarmData.message || '救生圈已释放!'}`,
                duration: 5000,
                showClose: true,
              })
            }
          } catch (e) {
            console.error('报警数据解析失败:', e)
          }
        })
      },

      onDisconnect: () => {
        connected.value = false
        console.log('WebSocket 已断开')
      },
    })

    stompClient.activate()
  }

  function disconnect() {
    try {
      stompClient?.deactivate()
    } catch (e) {
      // 忽略断开异常
    }
  }

  // 组件卸载时自动断开
  onUnmounted(disconnect)

  return { connected, frame, connect, disconnect }
}
