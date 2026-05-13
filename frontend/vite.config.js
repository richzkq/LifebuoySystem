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
        target: 'ws://10.151.51.174:8080',
        ws: true,
        changeOrigin: true
      },

      '/uploads': {
        target: 'http://10.151.51.174:8080',
        changeOrigin: true,
        secure: false
      },

      '/api': {
        target: 'http://10.151.51.174:8080',
        changeOrigin: true,
        secure: false
      }
    }
  }
})