<template>
  <div class="login-container">
    <div class="background-overlay"></div>

    <div class="glass-card">
      <div class="card-header">
        <div class="logo-wrapper">
          <div class="lifebuoy-icon">
            <el-icon><Platform /></el-icon>
          </div>
          <div class="pulse-ring"></div>
        </div>
        <h2 class="title">LIFEBUOY</h2>
        <p class="subtitle">web监控平台</p>
      </div>

      <el-form 
        ref="loginFormRef" 
        :model="loginForm" 
        :rules="rules" 
        class="login-form"
      >
        <el-form-item prop="username">
          <div class="input-container">
            <el-input 
              v-model="loginForm.username" 
              placeholder="管理员账号" 
              :prefix-icon="User"
              class="custom-input"
            />
          </div>
        </el-form-item>

        <el-form-item prop="password">
          <div class="input-container">
            <el-input 
              v-model="loginForm.password" 
              type="password" 
              placeholder="密码" 
              :prefix-icon="Lock"
              show-password
              class="custom-input"
              @keyup.enter="handleLogin"
            />
          </div>
        </el-form-item>

        <div class="form-options">
          <el-checkbox v-model="rememberMe" class="custom-checkbox">记住我</el-checkbox>
          <span class="forgot-link">重置密码?</span>
        </div>

        <el-form-item>
          <button 
            type="button" 
            class="action-btn" 
            :disabled="loading"
            @click="handleLogin"
          >
            <div class="btn-content">
              <span v-if="!loading">安全登录</span>
              <el-icon v-else class="is-loading"><Loading /></el-icon>
            </div>
            <div class="liquid"></div>
          </button>
        </el-form-item>
      </el-form>

      <div class="card-footer">
        <span class="status-dot"></span>
        <span class="status-text">SYSTEM SECURE & ONLINE</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { User, Lock, Platform, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { useRouter } from 'vue-router'

const router = useRouter()
const loginFormRef = ref(null)
const loading = ref(false)
const rememberMe = ref(false)

const loginForm = reactive({
  username: '',
  password: ''
})

const rules = reactive({
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入访问密钥', trigger: 'blur' }]
})

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
        const response = await request.post('/user/login', loginForm)
        const res = response.data
        if (res.code === 200) {
          ElMessage.success('身份验证成功')
          localStorage.setItem('token', res.data)
          router.push('/detection')
        } else {
          ElMessage.error(res.msg || '凭据错误')
        }
      } catch (error) {
        ElMessage.error('无法连接到服务器')
      } finally {
        loading.value = false
      }
    }
  })
}
</script>

<style scoped>


:root {
  --primary-orange: #FF5E00;
}

/* .login-container {
  position: relative;
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: flex-end; 
  padding-right: 15%; 
  align-items: center;
  background: url('../assets/backgroundImage.png') no-repeat center center;
  background-size: cover;
  overflow: hidden;
} */

.login-container {
  position: relative;
  width: 100vw;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: url('../assets/backgroundImage.png') no-repeat center center;
  background-size: cover;
  overflow: hidden;
  padding-left: 10%;     /* ← 这里调整数值即可控制卡片位置 */
}

.background-overlay {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  /* 调整遮罩，让卡片背后的色彩更自然 */
  background: radial-gradient(circle at 90% 50%, rgba(255, 255, 255, 0) 0%, rgba(15, 23, 42, 0.3) 100%);
  z-index: 1;
}

.glass-card {
  position: relative;
  z-index: 10;
  width: 280px; /* 进一步瘦身(v2) */   
  padding: 40px 35px;
  /* 修改2：更清透的背景 + 超大模糊值 */
  background: rgba(255, 255, 255, 0.08); 
  backdrop-filter: blur(40px) saturate(180%);
  -webkit-backdrop-filter: blur(40px) saturate(180%);
  
  border-radius: 35px;
  /* 修改3：边缘晕染感，细线边框 + 柔光阴影 */
  border: 1px solid rgba(255, 255, 255, 0.25);
  box-shadow: 
    0 25px 50px rgba(0, 0, 0, 0.15),            /* 基础深色阴影 */
    0 0 30px rgba(255, 255, 255, 0.1),         /* 内部光晕 */
    inset 0 0 2px rgba(255, 255, 255, 0.4);    /* 边缘高光线 */
  
  text-align: center;
  transition: all 0.5s ease;
}

.logo-wrapper {
  position: relative;
  width: 60px; height: 60px;
  margin: 0 auto 10px;
}

.lifebuoy-icon {
  width: 100%; height: 100%;
  background: var(--primary-orange);
  border-radius: 50%;
  display: flex; justify-content: center; align-items: center;
  font-size: 26px; color: white;
  z-index: 2; position: relative;
  box-shadow: 0 5px 15px rgba(255, 94, 0, 0.4);
}

.pulse-ring {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  border: 1.5px solid var(--primary-orange);
  border-radius: 50%;
  animation: pulse 3s infinite ease-out;
}

@keyframes pulse {
  0% { transform: scale(1); opacity: 0.5; }
  70% { transform: scale(2); opacity: 0; }
  100% { transform: scale(1); opacity: 0; }
}

.title {
  margin: 5px 0;
  color: #ffffff;
  font-size: 1.3rem;
  font-weight: 900;
  letter-spacing: 2px;
  text-shadow: 0 2px 10px rgba(0,0,0,0.3);
}

.subtitle {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
  margin-bottom: 30px;
  text-transform: uppercase;
}

/* 输入框清透化 */
.input-container {
  background: rgba(255, 255, 255, 0.05);
  border-radius: 14px;
  margin-bottom: 15px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: 0.3s;
}

.input-container:focus-within {
  background: rgba(255, 255, 255, 0.15);
  border-color: rgba(255, 255, 255, 0.4);
}

:deep(.custom-input .el-input__wrapper) {
  background: transparent !important;
  box-shadow: none !important;
  height: 44px;
}

:deep(.el-input__inner) {
  color: #ffffff !important;
}

:deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.4) !important;
}

.form-options {
  display: flex; justify-content: space-between;
  margin: -5px 5px 25px;
  font-size: 12px;
}

.custom-checkbox {
  color: rgba(255, 255, 255, 0.6) !important;
}

:deep(.el-checkbox__label) {
  color: rgba(255, 255, 255, 0.6) !important;
}

.forgot-link {
  color: rgba(255, 255, 255, 0.4);
  cursor: pointer;
}

/* 按钮高亮 */
.action-btn {
  position: relative;
  width: 100%; height: 48px;
  background: rgba(255, 255, 255, 0.95);
  color: #1e293b;
  border: none; border-radius: 14px;
  font-weight: 800; cursor: pointer;
  overflow: hidden; transition: 0.4s;
}

.liquid {
  position: absolute; top: 0; left: 0; width: 100%; height: 100%;
  background: var(--primary-orange);
  transform: translateY(100%);
  transition: 0.5s cubic-bezier(0.4, 0, 0.2, 1);
}

.action-btn:hover {
  color: #fff;
  transform: translateY(-2px);
  box-shadow: 0 10px 25px rgba(255, 94, 0, 0.4);
}

.action-btn:hover .liquid {
  transform: translateY(0);
}

.card-footer {
  margin-top: 30px; display: flex;
  align-items: center; justify-content: center; gap: 8px;
}

.status-dot {
  width: 6px; height: 6px;
  background: #10B981; border-radius: 50%;
  box-shadow: 0 0 12px #10B981;
}

.status-text {
  font-size: 9px; font-weight: bold;
  letter-spacing: 2px; color: rgba(255, 255, 255, 0.5);
}
</style>