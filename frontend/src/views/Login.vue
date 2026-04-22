<template>
  <div class="login-container">
    <!-- 背景水波纹动画层 -->
    <div class="water-wave wave1"></div>
    <div class="water-wave wave2"></div>
    <div class="water-wave wave3"></div>

    <!-- 登录卡片 -->
    <div class="glass-card">
      <div class="card-header">
        <div class="logo-circle">
          <el-icon class="marine-icon"><Platform /></el-icon>
        </div>
        <h2 class="title">IoT Lifebuoy Safety System</h2>
        <!-- <p class="subtitle">智能救生圈后台管理系统</p> -->
      </div>

      <el-form 
        ref="loginFormRef" 
        :model="loginForm" 
        :rules="rules" 
        class="login-form"
      >
        <el-form-item prop="username">
          <div class="neumorphic-input-wrapper">
            <el-input 
              v-model="loginForm.username" 
              placeholder="请输入账号" 
              :prefix-icon="User"
              class="neumorphic-input"
            />
          </div>
        </el-form-item>

        <el-form-item prop="password">
          <div class="neumorphic-input-wrapper">
            <el-input 
              v-model="loginForm.password" 
              type="password" 
              placeholder="请输入密码" 
              :prefix-icon="Lock"
              show-password
              class="neumorphic-input"
              @keyup.enter="handleLogin"
            />
          </div>
        </el-form-item>

        <el-form-item>
          <button 
            type="button" 
            class="liquid-btn" 
            :disabled="loading"
            @click="handleLogin"
          >
            <span class="btn-text">{{ loading ? '登录中...' : '登 录' }}</span>
            <div class="liquid-bg"></div>
          </button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { User, Lock, Platform } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { useRouter } from 'vue-router'

const router = useRouter()

const loginFormRef = ref(null)
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const rules = reactive({
  username: [
    { required: true, message: '账号不能为空', trigger: 'blur' },
    { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '密码不能为空', trigger: 'blur' },
    { min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
  ]
})

// Axios 配置（如果需要可以抽取到单独的 request.js）
const request = axios.create({
  baseURL: 'http://localhost:8080', 
  timeout: 5000
})

const handleLogin = async () => {
  if (!loginFormRef.value) return
  
  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const response = await request.post('/user/login', {
          username: loginForm.username,
          password: loginForm.password
        })

        const res = response.data
        if (res.code === 200) {
          ElMessage.success('登录成功')
          // 存储 Token
          localStorage.setItem('token', res.data)
          router.push('/dashboard')
        } else {
          ElMessage.error(res.msg || '登录失败')
        }
      } catch (error) {
        ElMessage.error('网络异常，请稍后重试')
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped>
/* 全局颜色变量 */
:root {
  --bg-white-blue: #EAF6FF;
  --light-blue: #D6F0FF;
  --btn-blue: #8FD3FF;
  --accent-blue: #4DA6FF;
  --card-white: #FFFFFF;
}

.login-container {
  position: relative;
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: rgb(248, 251, 255);
  overflow: hidden;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

/* 水波纹背景效果 (Liquid Water Waves) */
.water-wave {
  position: absolute;
  border-radius: 40% 60% 70% 30% / 40% 50% 60% 50%;
  background: rgba(255, 255, 255, 0.4);
  animation: waveAnimation 15s infinite linear;
  z-index: 0;
  filter: blur(10px);
}

.wave1 {
  width: 60vw;
  height: 60vw;
  top: -10vw;
  left: -10vw;
  background: linear-gradient(to right, rgba(214, 240, 255, 0.6), rgba(143, 211, 255, 0.3));
}

.wave2 {
  width: 50vw;
  height: 50vw;
  bottom: -15vw;
  right: -10vw;
  background: linear-gradient(to right, rgba(234, 246, 255, 0.5), rgba(77, 166, 255, 0.2));
  animation-duration: 20s;
  animation-direction: reverse;
}

.wave3 {
  width: 40vw;
  height: 40vw;
  bottom: 20vh;
  left: 20vw;
  background: linear-gradient(to right, rgba(255, 255, 255, 0.4), rgba(214, 240, 255, 0.4));
  animation-duration: 25s;
}

@keyframes waveAnimation {
  0% { transform: rotate(0deg) scale(1); }
  50% { transform: rotate(180deg) scale(1.05); }
  100% { transform: rotate(360deg) scale(1); }
}

/* 玻璃态卡片 (Glassmorphism + Neumorphism) */
.glass-card {
  position: relative;
  z-index: 10;
  width: 420px;
  padding: 50px 40px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.4) 0%, rgba(255, 255, 255, 0.05) 100%);
  border-radius: 24px;
  backdrop-filter: blur(40px);
  -webkit-backdrop-filter: blur(40px);
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-top: 1px solid rgba(255, 255, 255, 1);
  border-left: 1px solid rgba(255, 255, 255, 1);
  box-shadow: 
    0 20px 50px rgba(0, 0, 0, 0.08),
    0 10px 30px rgba(77, 166, 255, 0.15),
    -10px -10px 30px rgba(255, 255, 255, 1),
    inset 2px 2px 6px rgba(255, 255, 255, 0.8);
}

.card-header {
  text-align: center;
  margin-bottom: 40px;
}

.logo-circle {
  width: 80px;
  height: 80px;
  margin: 0 auto 20px;
  border-radius: 50%;
  background: #EAF6FF;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 
    8px 8px 16px rgba(143, 211, 255, 0.3),
    -8px -8px 16px rgba(255, 255, 255, 0.8);
}

.marine-icon {
  font-size: 40px;
  color: #4DA6FF;
}

.title {
  font-size: 24px;
  font-weight: 700;
  color: #2C3E50;
  margin: 0 0 8px;
  letter-spacing: 0.5px;
}

/* .subtitle {
  font-size: 14px;
  color: #64748B;
  margin: 0;
} */

/* 拟态输入框包裹器 */
.neumorphic-input-wrapper {
  width: 100%;
  border-radius: 12px;
  background: #EAF6FF;
  box-shadow: 
    inset 4px 4px 8px rgba(143, 211, 255, 0.3),
    inset -4px -4px 8px rgba(255, 255, 255, 0.9);
  padding: 4px;
}

/* 覆盖 Element Plus 输入框默认样式 */
:deep(.neumorphic-input .el-input__wrapper) {
  background-color: transparent !important;
  box-shadow: none !important;
  border: none !important;
  padding: 8px 15px;
}

:deep(.neumorphic-input .el-input__inner) {
  color: #2C3E50;
  font-weight: 500;
}

:deep(.neumorphic-input .el-input__inner::placeholder) {
  color: #94A3B8;
}

/* 流体高亮动画按钮 (Liquid Button) */
.liquid-btn {
  position: relative;
  width: 100%;
  height: 50px;
  margin-top: 20px;
  border: none;
  border-radius: 12px;
  background: #8FD3FF;
  color: #FFFFFF;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  overflow: hidden;
  box-shadow: 
    6px 6px 12px rgba(143, 211, 255, 0.4),
    -6px -6px 12px rgba(255, 255, 255, 0.9);
  transition: all 0.3s ease;
  outline: none;
}

.liquid-btn:hover {
  transform: translateY(-2px);
  box-shadow: 
    8px 8px 16px rgba(143, 211, 255, 0.5),
    -8px -8px 16px rgba(255, 255, 255, 0.9);
}

.liquid-btn:active {
  transform: translateY(1px);
  box-shadow: 
    inset 4px 4px 8px rgba(77, 166, 255, 0.4),
    inset -4px -4px 8px rgba(214, 240, 255, 0.5);
}

.btn-text {
  position: relative;
  z-index: 10;
  letter-spacing: 2px;
}

/* 按钮内流水动画 */
.liquid-bg {
  position: absolute;
  top: -50px;
  left: 0;
  width: 100%;
  height: 200%;
  background: #4DA6FF;
  border-radius: 40%;
  z-index: 1;
  transition: all 0.6s ease;
  transform: translateY(100%) rotate(0deg);
}

.liquid-btn:hover .liquid-bg {
  transform: translateY(-20%) rotate(180deg);
  animation: liquidRotate 3s linear infinite;
}

@keyframes liquidRotate {
  0% { transform: translateY(-20%) rotate(0deg); }
  100% { transform: translateY(-20%) rotate(360deg); }
}

/* 响应式调整 */
@media (max-width: 480px) {
  .glass-card {
    width: 90%;
    padding: 40px 20px;
  }
}
</style>
