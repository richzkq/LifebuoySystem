/**
 * 认证状态管理 (Pinia)
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import * as authApi from '@/api/auth'
import { STORAGE_KEY } from '@/config'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(STORAGE_KEY.TOKEN) || '')
  const username = ref('')

  const isAuthenticated = computed(() => !!token.value)

  async function login(loginUsername, password) {
    const res = await authApi.login(loginUsername, password)
    const data = res.data
    if (data.code === 200) {
      token.value = data.data
      username.value = loginUsername
      localStorage.setItem(STORAGE_KEY.TOKEN, data.data)
      return data
    } else {
      throw new Error(data.msg || '登录失败')
    }
  }

  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem(STORAGE_KEY.TOKEN)
    // 在组件中通过 useRouter 跳转，这里只清状态
  }

  return { token, username, isAuthenticated, login, logout }
})
