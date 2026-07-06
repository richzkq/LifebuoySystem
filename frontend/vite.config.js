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
        target: 'ws://10.184.201.174:8080',
        ws: true,
        changeOrigin: true,
      },
      '/ws-browser-frame': {
        target: 'ws://10.184.201.174:8080',
        ws: true,
        changeOrigin: true,
      },
      '/ws-frame': {
        target: 'ws://10.184.201.174:8080',
        ws: true,
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://10.184.201.174:8080',
        changeOrigin: true,
        secure: false,
      },
      '/api': {
        target: 'http://10.184.201.174:8080',
        changeOrigin: true,
        secure: false,
      },
      '/user': {
        target: 'http://10.184.201.174:8080',
        changeOrigin: true,
        secure: false,
      },
      '/device': {
        target: 'http://10.184.201.174:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
