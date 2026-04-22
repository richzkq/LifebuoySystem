import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  define: { global: 'window' },
  server: {
    proxy: {
      '/ws': {
        target: 'http://10.151.51.174:8080',
        ws: true,          // ← 关键，代理 WebSocket
        changeOrigin: true
      }
    }
  }
})
