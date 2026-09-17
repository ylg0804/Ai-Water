import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const backend = loadEnv(mode, '.', 'BACKEND_URL').BACKEND_URL || 'http://127.0.0.1:8080'
  const proxy = {
    '/api': { target: backend, changeOrigin: true },
    '/ws/realtime': { target: backend, changeOrigin: true, ws: true },
  }
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy,
      fs: { deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/.backend*.properties', '**/.tools/**'] },
    },
    preview: { proxy },
    build: {
      rollupOptions: {
        output: {
          manualChunks: { charts: ['echarts'], ui: ['element-plus'], vue: ['vue', 'vue-router'] },
        },
      },
    },
  }
})
