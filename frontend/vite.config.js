import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],

  define: {
    global: 'window',
  },

  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },

  server: {
    host: '0.0.0.0',
    proxy: {
      '/ws': {
        target: 'ws://47.83.199.93:8080',
        ws: true,
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false,
      },
      '/api': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false,
      },
      '/user': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false,
      },
      '/device': {
        target: 'http://47.83.199.93:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
