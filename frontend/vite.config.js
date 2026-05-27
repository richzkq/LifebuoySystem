import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  define: {
    global: 'window'
  },
  server: {
    host: '0.0.0.0',
    proxy: {
      '/ws': {
        target: 'ws://47.83.199.93:8080',
        ws: true,
        changeOrigin: true
      },
      '/alarm': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false
      },
      '/uploads': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false
      },
      '/api': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false
      }
    }
  }
})