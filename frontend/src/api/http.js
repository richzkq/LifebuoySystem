/**
 * 统一 Axios 实例
 * - 请求拦截器自动附加 token
 * - 响应拦截器统一处理 401 / 网络错误
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { STORAGE_KEY, UI } from '@/config'

const http = axios.create({
  baseURL: '',              // 开发环境走 Vite 代理，生产环境由反向代理处理
  timeout: UI.AXIOS_TIMEOUT,
})

// ============ 请求拦截器 ============
http.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem(STORAGE_KEY.TOKEN)
    if (token) {
      config.headers.token = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ============ 响应拦截器 ============
http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const { status } = error.response
      if (status === 401 || status === 403) {
        localStorage.removeItem(STORAGE_KEY.TOKEN)
        ElMessage.error('登录已过期，请重新登录')
        window.location.href = '/login'
      } else if (status >= 500) {
        ElMessage.error('服务器异常，请稍后再试')
      }
    } else {
      ElMessage.error('网络连接失败，请检查网络')
    }
    return Promise.reject(error)
  }
)

export default http
